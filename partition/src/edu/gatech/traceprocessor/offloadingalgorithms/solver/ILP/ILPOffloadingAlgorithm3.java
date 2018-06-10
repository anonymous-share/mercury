package edu.gatech.traceprocessor.offloadingalgorithms.solver.ILP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.gatech.traceprocessor.offloadingalgorithms.solver.BooleanVariable;
import edu.gatech.traceprocessor.offloadingalgorithms.solver.SMethod;
import edu.gatech.traceprocessor.offloadingalgorithms.solver.SVariable;
import edu.gatech.traceprocessor.parser.Configuration;
import edu.gatech.traceprocessor.parser.Data;
import edu.gatech.traceprocessor.parser.Instruction;
import edu.gatech.traceprocessor.parser.Method;
import edu.gatech.traceprocessor.parser.Program;
import edu.gatech.traceprocessor.utils.Pair;

/*
 * Stateful uni-directional with simple linear network model
 */
public class ILPOffloadingAlgorithm3 extends ILPOffloadingAlgorithm {
	private Map<Data,Set<SVariable>> varMap;

	final int V = 0;
	final int T1 = 1;
	final int T2 = 2;
	
	public ILPOffloadingAlgorithm3(Program p) {
		super(p);
	}

	@Override
	public double optimize(Program p) {
		generateProgramVarMap();
		double time = generateConstraintsAndSolve();
		return time;
	}

	private void generateProgramVarMap(){
		this.varMap = new HashMap<Data,Set<SVariable>>();
		for(Data d : program.getData().values()){
			Set<SVariable> svSet = new HashSet<SVariable>();
			varMap.put(d, svSet);
			for(List<Instruction> accessors : d.groupAccessors()){
				SVariable sv = new SVariable(d,accessors.get(0),accessors.subList(1, accessors.size()));
				svSet.add(sv);
			}
		}
	}
	
	public double generateConstraintsAndSolve(){
		for(SMethod sMeth : methVarMap.values()){
			Method meth = sMeth.getMethod();
			double localRuntimeWeight = Configuration.getLocalExclusiveExecutionTime(meth);
			double remoteRuntimeWeight = Configuration.getRemoteExclusiveExecutionTime(meth);

			BooleanVariable li = addSolverVar(sMeth, "L", localRuntimeWeight, L);

			BooleanVariable ri = addSolverVar(sMeth, "R", remoteRuntimeWeight, R);

			BooleanVariable di = null;
			if(!Configuration.ignoreNetworkLatency)
				di = addSolverVar(sMeth, "D", 2 * Configuration.getLatency(), D);

			//Constraint to pin this function to local device
			if(isMethodPinned(meth)){
				addFixedValueConstraint(li, 1, false);
			}

			//Constraint that li xor ri
			{
				List<BooleanVariable> bList = new ArrayList<BooleanVariable>();
				bList.add(li);
				bList.add(ri);
				addXORConstraint(bList, false);
			}

			//Constraints involving parent node in the call graph
			Method parentMeth = meth.getCaller();
			if(parentMeth != null){
				SMethod parentSMeth = methVarMap.get(parentMeth);

				BooleanVariable pLi = addSolverVar(parentSMeth, "L", L);

				BooleanVariable pRi = addSolverVar(parentSMeth, "R", R);

				//Constraint that rj => ri where j is the parent node of i
				addImpliesConstraint(pRi, ri, false);

				//Constraint that di = lj * ri where j is the parent node of i
				if(!Configuration.ignoreNetworkLatency)
					addANDConstraint(di, pLi, ri, false);
			}else{
				if(!Configuration.ignoreNetworkLatency)
					addImpliesConstraint(ri,di, false);
			}
		}

		if(!Configuration.ignoreNetworkLatency)
			for(Set<SVariable> svSet : varMap.values()){
				for(SVariable sv : svSet){
					double dataWeight = Configuration.getTransportTime(sv.getData());

					//Variable constraints to ensure that Vi is set to 1 if at least 
					//one writer and reader of Vi are on different partitions
					HashSet<Integer> varFuncIDs = new HashSet<Integer>();
					ArrayList<Pair<Double,BooleanVariable>> consL1 = new ArrayList<Pair<Double,BooleanVariable>>();
					ArrayList<Pair<Double,BooleanVariable>> consL2 = new ArrayList<Pair<Double,BooleanVariable>>();

					Method varWriter = sv.getWriter().getMethod();
					varFuncIDs.add(varWriter.getMethodID());
					SMethod varSWriter = methVarMap.get(varWriter);

					consL1.add(new Pair<Double, BooleanVariable>(1.0, varSWriter.getSolverVar(L)));
					consL2.add(new Pair<Double, BooleanVariable>(1.0, varSWriter.getSolverVar(R)));

					for(Instruction reader : sv.getReaders()){
						Method readerMeth = reader.getMethod();
						SMethod readerSMeth = methVarMap.get(readerMeth);
						if(varFuncIDs.add(readerMeth.getMethodID())){
							consL1.add(new Pair<Double, BooleanVariable>(1.0, readerSMeth.getSolverVar(L)));
							consL2.add(new Pair<Double, BooleanVariable>(1.0, readerSMeth.getSolverVar(R)));
						}
					}

					if(varFuncIDs.size() != 1){
						BooleanVariable vi = addSolverVar(sv, "V", dataWeight, V);

						BooleanVariable t1i = addSolverVar(sv, "T1_", 0, T1);

						BooleanVariable t2i = addSolverVar(sv, "T2_", 0, T2);

						consL1.add(new Pair<Double, BooleanVariable>((double) -varFuncIDs.size(), t1i));
						consL2.add(new Pair<Double, BooleanVariable>((double) -varFuncIDs.size(), t2i));
						BooleanConstraint cons1 = new BooleanConstraint(consL1, 0, varFuncIDs.size() - 1);
						BooleanConstraint cons2 = new BooleanConstraint(consL2, 0, varFuncIDs.size() - 1);
						constraints.add(cons1);
						constraints.add(cons2);

						{
							List<BooleanVariable> bList = new ArrayList<BooleanVariable>();
							bList.add(t1i);
							bList.add(t2i);
							bList.add(vi);
							addXORConstraint(bList, false);
						}
					}
				}
			}

		//Constraints for co-locating methods
		{
			for(Set<Method> SCC : coLocSCCs){
				BooleanVariable si = addSolverVar("S");
				ArrayList<Pair<Double,BooleanVariable>> consL = new ArrayList<Pair<Double,BooleanVariable>>();
				for(Method sccM : SCC){
					SMethod sccSM = methVarMap.get(sccM);
					consL.add(new Pair<Double, BooleanVariable>(1.0,sccSM.getSolverVar(L)));
				}
				consL.add(new Pair<Double, BooleanVariable>(-1.0 * SCC.size(),si));
				BooleanConstraint cons = new BooleanConstraint(consL, 0, 0);
				constraints.add(cons);
			}
		}

		//Constraints for limiting the number of static offloaded methods
		{
			if(Configuration.offloadingLimit >= 0){
				ArrayList<Pair<Double,BooleanVariable>> consM = new ArrayList<Pair<Double,BooleanVariable>>();
				for(Map.Entry<String, Set<Method>> entry : methNameMap.entrySet()){
					Set<Method> methWithSameName = entry.getValue();

					BooleanVariable xi = addSolverVar("X");
					consM.add(new Pair<Double, BooleanVariable>(1.0, xi));

					ArrayList<Pair<Double,BooleanVariable>> consL = new ArrayList<Pair<Double,BooleanVariable>>();
					for(Method tempMeth : methWithSameName){
						SMethod tempSMeth = methVarMap.get(tempMeth);
						consL.add(new Pair<Double, BooleanVariable>(1.0, tempSMeth.getSolverVar(R)));
					}
					consL.add(new Pair<Double, BooleanVariable>(-1.0 * methWithSameName.size(),xi));
					BooleanConstraint cons = new BooleanConstraint(consL, -1.0 * methWithSameName.size() + 1 , 0);
					constraints.add(cons);
				}
				BooleanConstraint cons = new BooleanConstraint(consM, 0, Configuration.offloadingLimit);
				constraints.add(cons);
			}
		}

		double objValue = runSolver(1);
		return objValue;
	}

	@Override
	protected void getMethsToBeOffloaded(double[] ILPSolution) {
		for(SMethod meth : methVarMap.values()){
			if(ILPSolution[meth.getSolverVar(R).id] == 1){
				toBeOffloaded.add(meth.getMethod());
			}
		}
	}

	@Override
	public boolean isStateful() {
		return true;
	}
}
