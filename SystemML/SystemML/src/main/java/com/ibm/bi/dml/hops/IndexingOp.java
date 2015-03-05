/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.hops;

import com.ibm.bi.dml.hops.rewrite.HopRewriteUtils;
import com.ibm.bi.dml.lops.Aggregate;
import com.ibm.bi.dml.lops.Data;
import com.ibm.bi.dml.lops.Group;
import com.ibm.bi.dml.lops.Lop;
import com.ibm.bi.dml.lops.LopsException;
import com.ibm.bi.dml.lops.RangeBasedReIndex;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.sql.sqllops.SQLLops;

//for now only works for range based indexing op
public class IndexingOp extends Hop 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	public static String OPSTRING = "rix"; //"Indexing";
	
	private boolean _rowLowerEqualsUpper = false;
	private boolean _colLowerEqualsUpper = false;
	
	private enum IndexingMethod { 
		CP_RIX, //in-memory range index
		MR_RIX, //general case range reindex
		MR_VRIX, //vector (row/col) range index
	};
	
	
	private IndexingOp() {
		//default constructor for clone
	}
	
	//right indexing doesn't really need the dimensionality of the left matrix
	//private static Lops dummy=new Data(null, Data.OperationTypes.READ, null, "-1", DataType.SCALAR, ValueType.INT, false);
	public IndexingOp(String l, DataType dt, ValueType vt, Hop inpMatrix, Hop inpRowL, Hop inpRowU, Hop inpColL, Hop inpColU, boolean passedRowsLEU, boolean passedColsLEU) {
		super(Kind.IndexingOp, l, dt, vt);

		getInput().add(0, inpMatrix);
		getInput().add(1, inpRowL);
		getInput().add(2, inpRowU);
		getInput().add(3, inpColL);
		getInput().add(4, inpColU);
		
		// create hops if one of them is null
		inpMatrix.getParent().add(this);
		inpRowL.getParent().add(this);
		inpRowU.getParent().add(this);
		inpColL.getParent().add(this);
		inpColU.getParent().add(this);
		
		// set information whether left indexing operation involves row (n x 1) or column (1 x m) matrix
		setRowLowerEqualsUpper(passedRowsLEU);
		setColLowerEqualsUpper(passedColsLEU);
	}
	
	
	public boolean getRowLowerEqualsUpper(){
		return _rowLowerEqualsUpper;
	}
	
	public boolean getColLowerEqualsUpper() {
		return _colLowerEqualsUpper;
	}
	
	public void setRowLowerEqualsUpper(boolean passed){
		_rowLowerEqualsUpper  = passed;
	}
	
	public void setColLowerEqualsUpper(boolean passed) {
		_colLowerEqualsUpper = passed;
	}

	@Override
	public Lop constructLops()
		throws HopsException, LopsException 
	{	
		if (getLops() == null) {
			
			Hop input = getInput().get(0);
			
			//rewrite remove unnecessary right indexing
			if( dimsKnown() && input.dimsKnown() 
				&& getDim1() == input.getDim1() && getDim2() == input.getDim2() )
			{
				setLops( input.constructLops() );
			}
			//actual lop construction, incl operator selection 
			else
			{
				try {
					ExecType et = optFindExecType();
					if(et == ExecType.MR) {
						IndexingMethod method = optFindIndexingMethod( _rowLowerEqualsUpper, _colLowerEqualsUpper,
								                                       input._dim1, input._dim2, _dim1, _dim2);
						
						Lop dummy = Data.createLiteralLop(ValueType.INT, Integer.toString(-1));
						RangeBasedReIndex reindex = new RangeBasedReIndex(
								input.constructLops(), getInput().get(1).constructLops(), getInput().get(2).constructLops(),
								getInput().get(3).constructLops(), getInput().get(4).constructLops(), dummy, dummy,
								getDataType(), getValueType(), et);
		
						reindex.getOutputParameters().setDimensions(getDim1(), getDim2(), 
								getRowsInBlock(), getColsInBlock(), getNnz());
						
						reindex.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
						
						if( method == IndexingMethod.MR_RIX )
						{
							Group group1 = new Group(
									reindex, Group.OperationTypes.Sort, DataType.MATRIX,
									getValueType());
							group1.getOutputParameters().setDimensions(getDim1(),
									getDim2(), getRowsInBlock(), getColsInBlock(), getNnz());
							
							group1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
			
							Aggregate agg1 = new Aggregate(
									group1, Aggregate.OperationTypes.Sum, DataType.MATRIX,
									getValueType(), et);
							agg1.getOutputParameters().setDimensions(getDim1(),
									getDim2(), getRowsInBlock(), getColsInBlock(), getNnz());
			
							agg1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
							
							setLops(agg1);
						}
						else //method == IndexingMethod.MR_VRIX
						{
							setLops(reindex);
						}
					}
					else {
						Lop dummy = Data.createLiteralLop(ValueType.INT, Integer.toString(-1));
						RangeBasedReIndex reindex = new RangeBasedReIndex(
								input.constructLops(), getInput().get(1).constructLops(), getInput().get(2).constructLops(),
								getInput().get(3).constructLops(), getInput().get(4).constructLops(), dummy, dummy,
								getDataType(), getValueType(), et);
						reindex.getOutputParameters().setDimensions(getDim1(), getDim2(),
								getRowsInBlock(), getColsInBlock(), getNnz());
						reindex.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
						setLops(reindex);
					}
				} catch (Exception e) {
					throw new HopsException(this.printErrorLocation() + "In IndexingOp Hop, error constructing Lops " , e);
				}
			}
		}
		
		return getLops();
	}

	@Override
	public String getOpString() {
		String s = new String("");
		s += OPSTRING;
		return s;
	}

	public void printMe() throws HopsException {
		if (getVisited() != VisitStatus.DONE) {
			super.printMe();
			for (Hop h : getInput()) {
				h.printMe();
			}
		}
		setVisited(VisitStatus.DONE);
	}

	public SQLLops constructSQLLOPs() throws HopsException {
		throw new HopsException(this.printErrorLocation() + "constructSQLLOPs should not be called for IndexingOp Hop \n");
	}
	
	@Override
	public boolean allowsAllExecTypes()
	{
		return true;
	}
	
	@Override
	protected void computeMemEstimate( MemoTable memo )
	{
		//default behavior
		super.computeMemEstimate(memo);
		
		//try to infer via worstcase input statistics (for the case of dims known
		//but nnz initially unknown)
		MatrixCharacteristics mcM1 = memo.getAllInputStats(getInput().get(0));
		if( dimsKnown() && mcM1.getNonZeros()>=0 ){
			long lnnz = mcM1.getNonZeros(); //worst-case output nnz
			double lOutMemEst = computeOutputMemEstimate( _dim1, _dim2, lnnz );
			if( lOutMemEst<_outputMemEstimate ){
				_outputMemEstimate = lOutMemEst;
				_memEstimate = getInputOutputSize();				
			}
		}		
	}
	
	@Override
	protected double computeOutputMemEstimate( long dim1, long dim2, long nnz )
	{		
		double sparsity = OptimizerUtils.getSparsity(dim1, dim2, nnz);
		return OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, sparsity);
	}
	
	@Override
	protected double computeIntermediateMemEstimate( long dim1, long dim2, long nnz )
	{
		return 0;
	}
	
	@Override
	protected long[] inferOutputCharacteristics( MemoTable memo )
	{
		long[] ret = null;
		
		Hop input = getInput().get(0); //original matrix
		MatrixCharacteristics mc = memo.getAllInputStats(input);
		if( mc != null ) 
		{
			long lnnz = mc.dimsKnown()?Math.min(mc.getRows()*mc.getCols(), mc.getNonZeros()):-1;
			//worst-case is input size, but dense
			ret = new long[]{mc.getRows(), mc.getCols(), lnnz};
			if( _rowLowerEqualsUpper ) ret[0]=1;
			if( _colLowerEqualsUpper ) ret[1]=1;	
		}
		
		return ret;
	}

	@Override
	protected ExecType optFindExecType() throws HopsException {
		
		checkAndSetForcedPlatform();

		if( _etypeForced != null ) 			
		{
			_etype = _etypeForced;
		}
		else
		{	
			if ( OptimizerUtils.isMemoryBasedOptLevel() ) {
				_etype = findExecTypeByMemEstimate();
			}
			else if ( getInput().get(0).areDimsBelowThreshold() )
			{
				_etype = ExecType.CP;
			}
			else
			{
				_etype = ExecType.MR;
			}
			
			//check for valid CP dimensions and matrix size
			checkAndSetInvalidCPDimsAndSize();
			
			//mark for recompile (forever)
			if( OptimizerUtils.ALLOW_DYN_RECOMPILATION && !dimsKnown(true) && _etype==ExecType.MR )
				setRequiresRecompile();
		}
		
		return _etype;
	}
	
	/**
	 * 
	 * @param singleRow
	 * @param singleCol
	 * @param m1_dim1
	 * @param m1_dim2
	 * @param m2_dim1
	 * @param m2_dim2
	 * @return
	 */
	private static IndexingMethod optFindIndexingMethod( boolean singleRow, boolean singleCol, long m1_dim1, long m1_dim2, long m2_dim1, long m2_dim2 )
	{
		if(    singleRow && m1_dim2 == m2_dim2 && m2_dim2!=-1
			|| singleCol && m1_dim1 == m2_dim1 && m2_dim1!=-1 )
		{
			return IndexingMethod.MR_VRIX;
		}
		
		return IndexingMethod.MR_RIX; //general case
	}
	
	@Override
	public void refreshSizeInformation()
	{
		Hop input1 = getInput().get(0); //original matrix
		Hop input2 = getInput().get(1); //inpRowL
		Hop input3 = getInput().get(2); //inpRowU
		Hop input4 = getInput().get(3); //inpColL
		Hop input5 = getInput().get(4); //inpColU
		
		//parse input information
		boolean allRows = 
			(    input2 instanceof LiteralOp && HopRewriteUtils.getIntValueSafe((LiteralOp)input2)==1 
			  && input3 instanceof UnaryOp && ((UnaryOp)input3).getOp() == OpOp1.NROW  );
		boolean allCols = 
			(    input4 instanceof LiteralOp && HopRewriteUtils.getIntValueSafe((LiteralOp)input4)==1 
			  && input5 instanceof UnaryOp && ((UnaryOp)input5).getOp() == OpOp1.NCOL );
		boolean constRowRange = (input2 instanceof LiteralOp && input3 instanceof LiteralOp);
		boolean constColRange = (input4 instanceof LiteralOp && input5 instanceof LiteralOp);
		
		//set dimension information
		if( _rowLowerEqualsUpper ) //ROWS
			setDim1(1);
		else if( allRows ) 
			setDim1(input1.getDim1());
		else if( constRowRange ){
			setDim1( HopRewriteUtils.getIntValueSafe((LiteralOp)input3)
					-HopRewriteUtils.getIntValueSafe((LiteralOp)input2)+1 );
		}
		if( _colLowerEqualsUpper ) //COLS
			setDim2(1);
		else if( allCols ) 
			setDim2(input1.getDim2());
		else if( constColRange ){
			setDim2( HopRewriteUtils.getIntValueSafe((LiteralOp)input5)
					-HopRewriteUtils.getIntValueSafe((LiteralOp)input4)+1 );
		} 
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException 
	{
		IndexingOp ret = new IndexingOp();	
		
		//copy generic attributes
		ret.clone(this, false);
		
		//copy specific attributes

		return ret;
	}
	
	@Override
	public boolean compare( Hop that )
	{		
		if(  that._kind!=Kind.IndexingOp 
			&& getInput().size() != that.getInput().size() )
		{
			return false;
		}
		
		return (  getInput().get(0) == that.getInput().get(0)
				&& getInput().get(1) == that.getInput().get(1)
				&& getInput().get(2) == that.getInput().get(2)
				&& getInput().get(3) == that.getInput().get(3)
				&& getInput().get(4) == that.getInput().get(4));
	}
}