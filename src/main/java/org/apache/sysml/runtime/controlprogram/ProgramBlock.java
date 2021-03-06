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

package org.apache.sysml.runtime.controlprogram;

import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.jmlc.JMLCUtils;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.hops.Hop;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.hops.recompile.Recompiler;
import org.apache.sysml.lops.Lop;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.parser.ParseInfo;
import org.apache.sysml.parser.StatementBlock;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.DMLScriptException;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject.UpdateType;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.instructions.Instruction;
import org.apache.sysml.runtime.instructions.cp.BooleanObject;
import org.apache.sysml.runtime.instructions.cp.Data;
import org.apache.sysml.runtime.instructions.cp.DoubleObject;
import org.apache.sysml.runtime.instructions.cp.IntObject;
import org.apache.sysml.runtime.instructions.cp.ScalarObject;
import org.apache.sysml.runtime.instructions.cp.StringObject;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.utils.Statistics;
import org.apache.sysml.yarn.DMLAppMasterUtils;


public class ProgramBlock implements ParseInfo
{
	public static final String PRED_VAR = "__pred";
	
	protected static final Log LOG = LogFactory.getLog(ProgramBlock.class.getName());
	private static final boolean CHECK_MATRIX_SPARSITY = false;

	protected Program _prog;		// pointer to Program this ProgramBlock is part of
	protected ArrayList<Instruction> _inst;

	//additional attributes for recompile
	protected StatementBlock _sb = null;
	protected long _tid = 0; //by default _t0

	public ProgramBlock(Program prog) {
		_prog = prog;
		_inst = new ArrayList<>();
	}

	////////////////////////////////////////////////
	// getters, setters and similar functionality
	////////////////////////////////////////////////

	public Program getProgram(){
		return _prog;
	}

	public void setProgram(Program prog){
		_prog = prog;
	}

	public StatementBlock getStatementBlock(){
		return _sb;
	}

	public void setStatementBlock( StatementBlock sb ){
		_sb = sb;
	}

	public  ArrayList<Instruction> getInstructions() {
		return _inst;
	}

	public Instruction getInstruction(int i) {
		return _inst.get(i);
	}

	public  void setInstructions( ArrayList<Instruction> inst ) {
		_inst = inst;
	}

	public void addInstruction(Instruction inst) {
		_inst.add(inst);
	}

	public void addInstructions(ArrayList<Instruction> inst) {
		_inst.addAll(inst);
	}

	public int getNumInstructions() {
		return _inst.size();
	}

	public void setThreadID( long id ){
		_tid = id;
	}


	//////////////////////////////////////////////////////////
	// core instruction execution (program block, predicate)
	//////////////////////////////////////////////////////////

	/**
	 * Executes this program block (incl recompilation if required).
	 *
	 * @param ec execution context
	 */
	public void execute(ExecutionContext ec)
	{
		ArrayList<Instruction> tmp = _inst;

		//dynamically recompile instructions if enabled and required
		try
		{
			if( DMLScript.isActiveAM() ) //set program block specific remote memory
				DMLAppMasterUtils.setupProgramBlockRemoteMaxMemory(this);

			long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
			if(    ConfigurationManager.isDynamicRecompilation()
				&& _sb != null
				&& _sb.requiresRecompilation() )
			{
				tmp = Recompiler.recompileHopsDag(
					_sb, _sb.getHops(), ec.getVariables(), null, false, true, _tid);
			}
			if( DMLScript.STATISTICS ){
				long t1 = System.nanoTime();
				Statistics.incrementHOPRecompileTime(t1-t0);
				if( tmp!=_inst )
					Statistics.incrementHOPRecompileSB();
			}
		}
		catch(Exception ex)
		{
			throw new DMLRuntimeException("Unable to recompile program block.", ex);
		}

		//actual instruction execution
		executeInstructions(tmp, ec);
	}

	/**
	 * Executes given predicate instructions (incl recompilation if required)
	 *
	 * @param inst list of instructions
	 * @param hops high-level operator
	 * @param requiresRecompile true if requires recompile
	 * @param retType value type of the return type
	 * @param ec execution context
	 * @return scalar object
	 */
	public ScalarObject executePredicate(ArrayList<Instruction> inst, Hop hops, boolean requiresRecompile, ValueType retType, ExecutionContext ec)
	{
		ArrayList<Instruction> tmp = inst;

		//dynamically recompile instructions if enabled and required
		try {
			long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
			if(    ConfigurationManager.isDynamicRecompilation()
				&& requiresRecompile )
			{
				tmp = Recompiler.recompileHopsDag(
					hops, ec.getVariables(), null, false, true, _tid);
				tmp = JMLCUtils.cleanupRuntimeInstructions(tmp, PRED_VAR);
			}
			if( DMLScript.STATISTICS ){
				long t1 = System.nanoTime();
				Statistics.incrementHOPRecompileTime(t1-t0);
				if( tmp!=inst )
					Statistics.incrementHOPRecompilePred();
			}
		}
		catch(Exception ex)
		{
			throw new DMLRuntimeException("Unable to recompile predicate instructions.", ex);
		}

		//actual instruction execution
		return executePredicateInstructions(tmp, retType, ec);
	}

	protected void executeInstructions(ArrayList<Instruction> inst, ExecutionContext ec) {
		for (int i = 0; i < inst.size(); i++) {
			//indexed access required due to dynamic add
			Instruction currInst = inst.get(i);
			//execute instruction
			ec.updateDebugState(i);
			executeSingleInstruction(currInst, ec);
		}
	}

	protected ScalarObject executePredicateInstructions(ArrayList<Instruction> inst, ValueType retType, ExecutionContext ec) {
		//execute all instructions (indexed access required due to debug mode)
		int pos = 0;
		for( Instruction currInst : inst ) {
			ec.updateDebugState(pos++);
			executeSingleInstruction(currInst, ec);
		}
		
		//get scalar return
		ScalarObject ret = (ScalarObject) ec.getScalarInput(PRED_VAR, retType, false);

		//check and correct scalar ret type (incl save double to int)
		if( ret.getValueType() != retType )
			switch( retType ) {
				case BOOLEAN: ret = new BooleanObject(ret.getBooleanValue()); break;
				case INT:	  ret = new IntObject(ret.getLongValue()); break;
				case DOUBLE:  ret = new DoubleObject(ret.getDoubleValue()); break;
				case STRING:  ret = new StringObject(ret.getStringValue()); break;
				default:
					//do nothing
			}

		//remove predicate variable
		ec.removeVariable(PRED_VAR);
		return ret;
	}

	private void executeSingleInstruction( Instruction currInst, ExecutionContext ec ) {
		try
		{
			// start time measurement for statistics
			long t0 = (DMLScript.STATISTICS || LOG.isTraceEnabled()) ?
				System.nanoTime() : 0;

			// pre-process instruction (debug state, inst patching, listeners)
			Instruction tmp = currInst.preprocessInstruction( ec );

			// process actual instruction
			tmp.processInstruction( ec );

			// post-process instruction (debug)
			tmp.postprocessInstruction( ec );

			// maintain aggregate statistics
			if( DMLScript.STATISTICS) {
				Statistics.maintainCPHeavyHitters(
					tmp.getExtendedOpcode(), System.nanoTime()-t0);
			}
			if (DMLScript.JMLC_MEM_STATISTICS && DMLScript.FINEGRAINED_STATISTICS)
				ec.getVariables().getPinnedDataSize();

			// optional trace information (instruction and runtime)
			if( LOG.isTraceEnabled() ) {
				long t1 = System.nanoTime();
				String time = String.format("%.3f",((double)t1-t0)/1000000000);
				LOG.trace("Instruction: "+ tmp + " (executed in " + time + "s).");
			}

			// optional check for correct nnz and sparse/dense representation of all
			// variables in symbol table (for tracking source of wrong representation)
			if( CHECK_MATRIX_SPARSITY ) {
				checkSparsity( tmp, ec.getVariables() );
			}
		}
		catch (Exception e)
		{
			if (!DMLScript.ENABLE_DEBUG_MODE) {
				if ( e instanceof DMLScriptException)
					throw (DMLScriptException)e;
				else
					throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error evaluating instruction: " + currInst.toString() , e);
			}
			else {
				ec.handleDebugException(e);
			}
		}
	}

	protected UpdateType[] prepareUpdateInPlaceVariables(ExecutionContext ec, long tid) {
		if( _sb == null || _sb.getUpdateInPlaceVars().isEmpty() )
			return null;

		ArrayList<String> varnames = _sb.getUpdateInPlaceVars();
		UpdateType[] flags = new UpdateType[varnames.size()];
		for( int i=0; i<flags.length; i++ ) {
			String varname = varnames.get(i);
			if( !ec.isMatrixObject(varname) )
				continue;
			MatrixObject mo = ec.getMatrixObject(varname);
			flags[i] = mo.getUpdateType();
			//create deep copy if required and if it fits in thread-local mem budget
			if( flags[i]==UpdateType.COPY && OptimizerUtils.getLocalMemBudget()/2 >
				OptimizerUtils.estimateSizeExactSparsity(mo.getMatrixCharacteristics())) {
				MatrixObject moNew = new MatrixObject(mo);
				MatrixBlock mbVar = mo.acquireRead();
				moNew.acquireModify( !mbVar.isInSparseFormat() ? new MatrixBlock(mbVar) :
					new MatrixBlock(mbVar, MatrixBlock.DEFAULT_INPLACE_SPARSEBLOCK, true) );
				moNew.setFileName(mo.getFileName()+Lop.UPDATE_INPLACE_PREFIX+tid);
				mo.release();
				moNew.release();
				moNew.setUpdateType(UpdateType.INPLACE);
				//cleanup old variable (e.g., remove from buffer pool)
				if( ec.removeVariable(varname) != null )
					ec.cleanupCacheableData(mo);
				ec.setVariable(varname, moNew);
			}
		}

		return flags;
	}

	protected void resetUpdateInPlaceVariableFlags(ExecutionContext ec, UpdateType[] flags) {
		if( flags == null )
			return;
		//reset update-in-place flag to pre-loop status
		ArrayList<String> varnames = _sb.getUpdateInPlaceVars();
		for( int i=0; i<varnames.size(); i++ )
			if( ec.getVariable(varnames.get(i)) != null && flags[i] !=null ) {
				MatrixObject mo = ec.getMatrixObject(varnames.get(i));
				mo.setUpdateType(flags[i]);
			}
	}
	
	private static void checkSparsity( Instruction lastInst, LocalVariableMap vars )
	{
		for( String varname : vars.keySet() )
		{
			Data dat = vars.get(varname);
			if( dat instanceof MatrixObject )
			{
				MatrixObject mo = (MatrixObject)dat;
				if( mo.isDirty() && !mo.isPartitioned() )
				{
					MatrixBlock mb = mo.acquireRead();
					boolean sparse1 = mb.isInSparseFormat();
					long nnz1 = mb.getNonZeros();
					synchronized( mb ) { //potential state change
						mb.recomputeNonZeros();
						mb.examSparsity();

					}
					if( mb.isInSparseFormat() && mb.isAllocated() ) {
						mb.getSparseBlock().checkValidity(mb.getNumRows(),
							mb.getNumColumns(), mb.getNonZeros(), true);
					}

					boolean sparse2 = mb.isInSparseFormat();
					long nnz2 = mb.getNonZeros();
					mo.release();

					if( nnz1 != nnz2 )
						throw new DMLRuntimeException("Matrix nnz meta data was incorrect: ("+varname+", actual="+nnz1+", expected="+nnz2+", inst="+lastInst+")");

					if( sparse1 != sparse2 && mb.isAllocated() )
						throw new DMLRuntimeException("Matrix was in wrong data representation: ("+varname+", actual="+sparse1+", expected="+sparse2 +
								", nrow="+mb.getNumRows()+", ncol="+mb.getNumColumns()+", nnz="+nnz1+", inst="+lastInst+")");
				}
			}
		}
	}

	///////////////////////////////////////////////////////////////////////////
	// store position information for program blocks
	///////////////////////////////////////////////////////////////////////////

	public String _filename;
	public int _beginLine, _beginColumn;
	public int _endLine, _endColumn;
	public String _text;

	public void setFilename(String passed)    { _filename = passed;   }
	public void setBeginLine(int passed)    { _beginLine = passed;   }
	public void setBeginColumn(int passed) 	{ _beginColumn = passed; }
	public void setEndLine(int passed) 		{ _endLine = passed;   }
	public void setEndColumn(int passed)	{ _endColumn = passed; }
	public void setText(String text) { _text = text; }

	public String getFilename()	{ return _filename;   }
	public int getBeginLine()	{ return _beginLine;   }
	public int getBeginColumn() { return _beginColumn; }
	public int getEndLine() 	{ return _endLine;   }
	public int getEndColumn()	{ return _endColumn; }
	public String getText() { return _text; }

	public String printBlockErrorLocation(){
		return "ERROR: Runtime error in program block generated from statement block between lines " + _beginLine + " and " + _endLine + " -- ";
	}

	/**
	 * Set parse information.
	 *
	 * @param parseInfo
	 *            parse information, such as beginning line position, beginning
	 *            column position, ending line position, ending column position,
	 *            text, and filename
	 * @param filename
	 *            the DML/PYDML filename (if it exists)
	 */
	public void setParseInfo(ParseInfo parseInfo) {
		_beginLine = parseInfo.getBeginLine();
		_beginColumn = parseInfo.getBeginColumn();
		_endLine = parseInfo.getEndLine();
		_endColumn = parseInfo.getEndColumn();
		_text = parseInfo.getText();
		_filename = parseInfo.getFilename();
	}

}
