/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;

import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.matrix.data.CSVFileFormatProperties;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;

/**
 * Parallel version of ReaderTextCSV.java. To summarize, we do two passes in
 * order to compute row offsets and the actual read. We accordingly create count
 * and read tasks and use fixed-size thread pools to execute these tasks. If the
 * target matrix is dense, the inserts are done lock-free. In contrast to
 * textcell parallel read, we also do lock-free inserts. If the matrix is
 * sparse, because splits contain row partitioned lines and hence there is no
 * danger of lost updates. Note, there is also no sorting of sparse rows
 * required because data comes in sorted order per row.
 * 
 */
public class ReaderTextCSVParallel extends MatrixReader 
{
	private CSVFileFormatProperties _props = null;
	private int _numThreads = 1;

	private SplitOffsetInfos _offsets = null;

	public ReaderTextCSVParallel(CSVFileFormatProperties props) {
		_numThreads = OptimizerUtils.getParallelTextReadParallelism();
		_props = props;
	}

	@Override
	public MatrixBlock readMatrixFromHDFS(String fname, long rlen, long clen,
			int brlen, int bclen, long estnnz) 
		throws IOException, DMLRuntimeException 
	{
		// prepare file access
		JobConf job = new JobConf(ConfigurationManager.getCachedJobConf());
		FileSystem fs = FileSystem.get(job);
		Path path = new Path(fname);

		FileInputFormat.addInputPath(job, path);
		TextInputFormat informat = new TextInputFormat();
		informat.configure(job);

		InputSplit[] splits = informat.getSplits(job, _numThreads);
		splits = IOUtilFunctions.sortInputSplits(splits);

		// check existence and non-empty file
		checkValidInputFile(fs, path);

		// allocate output matrix block
		// First Read Pass (count rows/cols, determine offsets, allocate matrix block)
		MatrixBlock ret = computeCSVSizeAndCreateOutputMatrixBlock(splits,
				path, job, _props.hasHeader(), _props.getDelim(), estnnz);
		rlen = ret.getNumRows();
		clen = ret.getNumColumns();

		// Second Read Pass (read, parse strings, append to matrix block)
		readCSVMatrixFromHDFS(splits, path, job, ret, rlen, clen, brlen, bclen,
				_props.hasHeader(), _props.getDelim(), _props.isFill(),
				_props.getFillValue());
		
		//post-processing (representation-specific, change of sparse/dense block representation)
		// - no sorting required for CSV because it is read in sorted order per row
		// - nnz explicitly maintained in parallel for the individual splits
		ret.examSparsity();

		// sanity check for parallel row count (since determined internally)
		if (rlen > 0 && rlen != ret.getNumRows())
			throw new DMLRuntimeException("Read matrix inconsistent with given meta data: "
					+ "expected nrow="+ rlen + ", real nrow=" + ret.getNumRows());

		return ret;
	}

	private void readCSVMatrixFromHDFS(InputSplit[] splits, Path path, JobConf job, 
			MatrixBlock dest, long rlen, long clen, int brlen, int bclen, 
			boolean hasHeader, String delim, boolean fill, double fillValue) 
		throws IOException 
	{
		FileInputFormat.addInputPath(job, path);
		TextInputFormat informat = new TextInputFormat();
		informat.configure(job);

		ExecutorService pool = Executors.newFixedThreadPool(_numThreads);

		try 
		{
			// create read tasks for all splits
			ArrayList<CSVReadTask> tasks = new ArrayList<CSVReadTask>();
			int splitCount = 0;
			for (InputSplit split : splits) {
				tasks.add( new CSVReadTask(split, _offsets, informat, job, dest, 
					rlen, clen, hasHeader, delim, fill, fillValue, splitCount++) );
			}
			pool.invokeAll(tasks);
			pool.shutdown();

			// check return codes and aggregate nnz
			long lnnz = 0;
			for (CSVReadTask rt : tasks) {
				lnnz += rt.getPartialNnz();
				if (!rt.getReturnCode()) {
					Exception err = rt.getException();
					throw new IOException("Read task for csv input failed: "+ err.toString(), err);
				}
			}
			dest.setNonZeros(lnnz);
		} 
		catch (Exception e) {
			throw new IOException("Threadpool issue, while parallel read.", e);
		}
	}

	private MatrixBlock computeCSVSizeAndCreateOutputMatrixBlock(
			InputSplit[] splits, Path path, JobConf job, boolean hasHeader,
			String delim, long estnnz) throws IOException, DMLRuntimeException 
	{
		int nrow = 0;
		int ncol = 0;
		
		FileInputFormat.addInputPath(job, path);
		TextInputFormat informat = new TextInputFormat();
		informat.configure(job);

		// count no of entities in the first non-header row
		LongWritable key = new LongWritable();
		Text oneLine = new Text();
		RecordReader<LongWritable, Text> reader = informat
				.getRecordReader(splits[0], job, Reporter.NULL);
		try {
			if (reader.next(key, oneLine)) {
				String cellStr = oneLine.toString().trim();
				ncol = StringUtils.countMatches(cellStr, delim) + 1;
			}
		} 
		finally {
			IOUtilFunctions.closeSilently(reader);
		}

		// count rows in parallel per split
		try 
		{
			ExecutorService pool = Executors.newFixedThreadPool(_numThreads);
			ArrayList<CountRowsTask> tasks = new ArrayList<CountRowsTask>();
			for (InputSplit split : splits) {
				tasks.add(new CountRowsTask(split, informat, job, hasHeader));
				hasHeader = false;
			}
			pool.invokeAll(tasks);
			pool.shutdown();

			// collect row counts for offset computation
			// early error notify in case not all tasks successful
			_offsets = new SplitOffsetInfos(tasks.size());
			for (CountRowsTask rt : tasks) {
				if (!rt.getReturnCode())
					throw new IOException("Count task for csv input failed: "+ rt.getErrMsg());
				_offsets.setOffsetPerSplit(tasks.indexOf(rt), nrow);
				_offsets.setLenghtPerSplit(tasks.indexOf(rt), rt.getRowCount());
				nrow = nrow + rt.getRowCount();
			}
		} 
		catch (Exception e) {
			throw new IOException("Threadpool Error " + e.getMessage(), e);
		}

		// allocate target matrix block based on given size; 
		// need to allocate sparse as well since lock-free insert into target
		return createOutputMatrixBlock(nrow, ncol, nrow, ncol, estnnz, true, true);
	}

	private static class SplitOffsetInfos {
		// offset & length info per split
		private int[] offsetPerSplit = null;
		private int[] lenghtPerSplit = null;

		public SplitOffsetInfos(int numSplits) {
			lenghtPerSplit = new int[numSplits];
			offsetPerSplit = new int[numSplits];
		}

		public int getLenghtPerSplit(int split) {
			return lenghtPerSplit[split];
		}

		public void setLenghtPerSplit(int split, int r) {
			lenghtPerSplit[split] = r;
		}

		public int getOffsetPerSplit(int split) {
			return offsetPerSplit[split];
		}

		public void setOffsetPerSplit(int split, int o) {
			offsetPerSplit[split] = o;
		}
	}

	private static class CountRowsTask implements Callable<Object> 
	{
		private InputSplit _split = null;
		private TextInputFormat _informat = null;
		private JobConf _job = null;
		private boolean _rc = true;
		private String _errMsg = null;
		private int _nrows = -1;
		private boolean _hasHeader = false;

		public CountRowsTask(InputSplit split, TextInputFormat informat,
				JobConf job, boolean hasHeader) {
			_split = split;
			_informat = informat;
			_job = job;
			_nrows = 0;
			_hasHeader = hasHeader;
		}

		public boolean getReturnCode() {
			return _rc;
		}

		public int getRowCount() {
			return _nrows;
		}
		
		public String getErrMsg() {
			return _errMsg;
		}

		@Override
		public Object call() 
			throws Exception 
		{
			RecordReader<LongWritable, Text> reader = _informat.getRecordReader(_split, _job, Reporter.NULL);
			LongWritable key = new LongWritable();
			Text oneLine = new Text();

			try {
				// count rows from the first non-header row
				if (_hasHeader) {
					reader.next(key, oneLine);
				}
				while (reader.next(key, oneLine)) {
					_nrows++;
				}
			} 
			catch (Exception e) {
				_rc = false;
				_errMsg = "RecordReader error CSV format. split: "+ _split.toString() + e.getMessage();
				throw new IOException(_errMsg);
			} 
			finally {
				IOUtilFunctions.closeSilently(reader);
			}

			return null;
		}
	}

	private static class CSVReadTask implements Callable<Object> 
	{
		private InputSplit _split = null;
		private SplitOffsetInfos _splitoffsets = null;
		private boolean _sparse = false;
		private TextInputFormat _informat = null;
		private JobConf _job = null;
		private MatrixBlock _dest = null;
		private long _rlen = -1;
		private long _clen = -1;
		private boolean _isFirstSplit = false;
		private boolean _hasHeader = false;
		private boolean _fill = false;
		private double _fillValue = 0;
		private String _delim = null;
		private int _splitCount = 0;
		
		private boolean _rc = true;
		private Exception _exception = null;
		private long _nnz;
		
		public CSVReadTask(InputSplit split, SplitOffsetInfos offsets,
				TextInputFormat informat, JobConf job, MatrixBlock dest,
				long rlen, long clen, boolean hasHeader, String delim,
				boolean fill, double fillValue, int splitCount) 
		{
			_split = split;
			_splitoffsets = offsets; // new SplitOffsetInfos(offsets);
			_sparse = dest.isInSparseFormat();
			_informat = informat;
			_job = job;
			_dest = dest;
			_rlen = rlen;
			_clen = clen;
			_isFirstSplit = (splitCount == 0);
			_hasHeader = hasHeader;
			_fill = fill;
			_fillValue = fillValue;
			_delim = delim;
			_rc = true;
			_splitCount = splitCount;
		}

		public boolean getReturnCode() {
			return _rc;
		}

		public Exception getException() {
			return _exception;
		}
		
		public long getPartialNnz() {
			return _nnz;
		}

		@Override
		public Object call() 
			throws Exception 
		{
			int row = 0;
			int col = 0;
			double cellValue = 0;
			long lnnz = 0;
			
			try 
			{
				RecordReader<LongWritable, Text> reader = _informat.getRecordReader(_split, _job, Reporter.NULL);
				LongWritable key = new LongWritable();
				Text value = new Text();
				
				// skip the header line
				if (_isFirstSplit && _hasHeader) {
					reader.next(key, value);
				}

				boolean noFillEmpty = false;
				row = _splitoffsets.getOffsetPerSplit(_splitCount);

				try {
					if (_sparse) // SPARSE<-value
					{
						while (reader.next(key, value)) // foreach line
						{
							String cellStr = value.toString().trim();
							String[] parts = IOUtilFunctions.split(cellStr, _delim);
							col = 0;

							for (String part : parts) // foreach cell
							{
								part = part.trim();
								if (part.isEmpty()) {
									noFillEmpty |= !_fill;
									cellValue = _fillValue;
								} 
								else {
									cellValue = IOUtilFunctions.parseDoubleParallel(part);
								}

								if( cellValue != 0 ) {
									_dest.appendValue(row, col, cellValue);
									lnnz++;
								}
								col++;
							}

							// sanity checks (number of columns, fill values)
							IOUtilFunctions.checkAndRaiseErrorCSVEmptyField(cellStr, _fill, noFillEmpty);
							IOUtilFunctions.checkAndRaiseErrorCSVNumColumns(_split.toString(), cellStr, parts, _clen);
							
							row++;
						}
					} 
					else // DENSE<-value
					{
						while (reader.next(key, value)) // foreach line
						{
							String cellStr = value.toString().trim();
							String[] parts = IOUtilFunctions.split(cellStr, _delim);
							col = 0;

							for (String part : parts) // foreach cell
							{
								part = part.trim();
								if (part.isEmpty()) {
									noFillEmpty |= !_fill;
									cellValue = _fillValue;
								} 
								else {
									cellValue = IOUtilFunctions.parseDoubleParallel(part);
								}
								if( cellValue != 0 ) {
									_dest.setValueDenseUnsafe(row, col, cellValue);
									lnnz++;
								}
								col++;
							}

							// sanity checks (number of columns, fill values)
							IOUtilFunctions.checkAndRaiseErrorCSVEmptyField(cellStr, _fill, noFillEmpty);
							IOUtilFunctions.checkAndRaiseErrorCSVNumColumns(_split.toString(), cellStr, parts, _clen);
							
							row++;
						}
					}

					// sanity checks (number of rows)
					if (row != (_splitoffsets.getOffsetPerSplit(_splitCount) + _splitoffsets.getLenghtPerSplit(_splitCount)) ) 
					{
						throw new IOException("Incorrect number of rows ("+ row+ ") found in delimited file ("
										+ (_splitoffsets.getOffsetPerSplit(_splitCount) 
										+ _splitoffsets.getLenghtPerSplit(_splitCount))+ "): " + value);
					}
				} 
				finally {
					if (reader != null)
						reader.close();
				}
			} 
			catch (Exception ex) {
				// central error handling (return code, message)
				_rc = false;
				_exception = ex;

				// post-mortem error handling and bounds checking
				if (row < 0 || row + 1 > _rlen || col < 0 || col + 1 > _clen) {
					String errMsg = "CSV cell [" + (row + 1) + "," + (col + 1)+ "] " + 
							"out of overall matrix range [1:" + _rlen+ ",1:" + _clen + "]. " + ex.getMessage();
					throw new IOException(errMsg, _exception);
				} 
				else {
					String errMsg = "Unable to read matrix in text CSV format. "+ ex.getMessage();
					throw new IOException(errMsg, _exception);
				}
			}

			//post processing
			_nnz = lnnz;
			
			return null;
		}
	}
}
