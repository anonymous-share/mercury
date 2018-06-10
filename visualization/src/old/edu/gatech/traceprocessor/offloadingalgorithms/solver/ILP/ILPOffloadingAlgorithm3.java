package old.edu.gatech.traceprocessor.offloadingalgorithms.solver.ILP;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import old.edu.gatech.traceprocessor.Configuration;
import old.edu.gatech.traceprocessor.Function;
import old.edu.gatech.traceprocessor.VarEntry;
import old.edu.gatech.traceprocessor.offloadingalgorithms.solver.BooleanVariable;
import old.edu.gatech.traceprocessor.offloadingalgorithms.solver.SFunction;
import old.edu.gatech.traceprocessor.offloadingalgorithms.solver.SVariable;
import edu.gatech.traceprocessor.utils.Pair;

/*
 * Stateful uni-directional with simple linear network model
 */
public class ILPOffloadingAlgorithm3 extends ILPOffloadingAlgorithm {


	final int L = 0;
	final int R = 1;
	final int D = 2;
	
	final int V = 0;
	final int T1 = 1;
	final int T2 = 2;
	
	public ILPOffloadingAlgorithm3(Function forest, Set<VarEntry> programVarTable, Set<Set<Function>> coLocSCCs) {
		super(forest, programVarTable, coLocSCCs);
	}

	@Override
	public double optimize(Function root) {
		generateProgramVarMap();
//		generateDataStatsRecursive2(root);
//		Utils.printLog("CumulativeDataStatsGenerated");
		double time = generateConstraintsAndSolve();
		return time;
	}

	public double generateConstraintsAndSolve(){

	//	for(Function func : funcMap.keySet()){
		for(Function func : funcIDMap.values()){
		//	SFunction extFunc = funcMap.get(func);
			SFunction extFunc = (SFunction)func;
			double localRuntimeWeight = Configuration.getLocalRemainderExecutionTime(extFunc);
			double remoteRuntimeWeight = Configuration.getRemoteRemainderExecutionTime(extFunc);

			BooleanVariable li = addSolverVar(extFunc, "L", localRuntimeWeight, L);

			BooleanVariable ri = addSolverVar(extFunc, "R", remoteRuntimeWeight, R);

			BooleanVariable di = addSolverVar(extFunc, "D", 2 * Configuration.latency, D);

			//Constraint to pin this function to local device
			if(!func.isOffloadable()){
				addFixedValueConstraint(li, 1);
			}

			//Constraint that li xor ri
			{
				List<BooleanVariable> bList = new ArrayList<BooleanVariable>();
				bList.add(li);
				bList.add(ri);
				addXORConstraint(bList);
			}

			//Constraints involving parent node in the call graph
			Function parentFunc = func.getParent();
			if(parentFunc.getParent() != null){
		//		SFunction extParentFunc = funcMap.get(parentFunc);
		//		assert extParentFunc!=null;
				SFunction extParentFunc = (SFunction)parentFunc;

				BooleanVariable pLi = addSolverVar(extParentFunc, "L", L);

				BooleanVariable pRi = addSolverVar(extParentFunc, "R", R);

				//Constraint that rj => ri where j is the parent node of i
				addImpliesConstraint(pRi, ri);

				//Constraint that di = lj * ri where j is the parent node of i
				addANDConstraint(di, pLi, ri);
			}
		}

		for(VarEntry v : programVarTable){
			SVariable extVar = programVarMap.get(v);
			double dataWeight = Configuration.getTransportTime(v);

			//Variable constraints to ensure that Vi is set to 1 if at least 
			//one writer and reader of Vi are on different partitions
			HashSet<Integer> varFuncIDs = new HashSet<Integer>();
			ArrayList<Pair<Double,BooleanVariable>> consL1 = new ArrayList<Pair<Double,BooleanVariable>>();
			ArrayList<Pair<Double,BooleanVariable>> consL2 = new ArrayList<Pair<Double,BooleanVariable>>();

		//	SFunction varWriter = funcMap.get(v.getWriter());
		//	varFuncIDs.add(varWriter.getId());
			
			SFunction varWriter = (SFunction)(v.getWriter());
			varFuncIDs.add(varWriter.getTreePostionID());

			consL1.add(new Pair<Double, BooleanVariable>(1.0, varWriter.getSolverVar(L)));
			consL2.add(new Pair<Double, BooleanVariable>(1.0, varWriter.getSolverVar(R)));

			for(Function reader : v.getReaders()){
			//	SFunction varReader = funcMap.get(reader);
			//	if(varFuncIDs.add(varReader.getId())){
				SFunction varReader = (SFunction)reader;
				if(varFuncIDs.add(varReader.getTreePostionID())){
					consL1.add(new Pair<Double, BooleanVariable>(1.0, varReader.getSolverVar(L)));
					consL2.add(new Pair<Double, BooleanVariable>(1.0, varReader.getSolverVar(R)));
				}
			}

			if(varFuncIDs.size() != 1){
				BooleanVariable vi = addSolverVar(extVar, "V", dataWeight, V);

				BooleanVariable t1i = addSolverVar(extVar, "T1_", 0, T1);

				BooleanVariable t2i = addSolverVar(extVar, "T2_", 0, T2);

				consL1.add(new Pair<Double, BooleanVariable>((double) -varFuncIDs.size(), t1i));
				consL2.add(new Pair<Double, BooleanVariable>((double) -varFuncIDs.size(), t2i));
				ILPConstraint cons1 = new ILPConstraint(consL1, 0, varFuncIDs.size() - 1);
				ILPConstraint cons2 = new ILPConstraint(consL2, 0, varFuncIDs.size() - 1);
				constraints.add(cons1);
				constraints.add(cons2);

				{
					List<BooleanVariable> bList = new ArrayList<BooleanVariable>();
					bList.add(t1i);
					bList.add(t2i);
					bList.add(vi);
					addXORConstraint(bList);
				}
			}
		}
		
		//Constraints for co-locating methods
		{
			for(Set<Function> SCC : coLocSCCs){
				BooleanVariable si = addSolverVar("S");
				ArrayList<Pair<Double,BooleanVariable>> consL = new ArrayList<Pair<Double,BooleanVariable>>();
				for(Function f : SCC){
					consL.add(new Pair<Double, BooleanVariable>(1.0, ((SFunction)f).getSolverVar(L)));
				}
				consL.add(new Pair<Double, BooleanVariable>(-1.0 * SCC.size(),si));
				ILPConstraint cons = new ILPConstraint(consL, 0, 0);
				constraints.add(cons);
			}
		}

		//Constraints for limiting the number of static offloaded methods
		{
			if(Configuration.offloadingLimit >= 0){
				ArrayList<Pair<Double,BooleanVariable>> consM = new ArrayList<Pair<Double,BooleanVariable>>();
				for(String fName : funcNameMap.keySet()){
					Set<Function> funcWithSameNames = funcNameMap.get(fName);

					BooleanVariable xi = addSolverVar("X");
					consM.add(new Pair<Double, BooleanVariable>(1.0, xi));

					ArrayList<Pair<Double,BooleanVariable>> consL = new ArrayList<Pair<Double,BooleanVariable>>();
					for(Function f : funcWithSameNames){
						consL.add(new Pair<Double, BooleanVariable>(1.0, ((SFunction)f).getSolverVar(R)));
					}
					consL.add(new Pair<Double, BooleanVariable>(-1.0 * funcWithSameNames.size(),xi));
					ILPConstraint cons = new ILPConstraint(consL, -1.0 * funcWithSameNames.size() + 1 , 0);
					constraints.add(cons);
				}
				ILPConstraint cons = new ILPConstraint(consM, 0, Configuration.offloadingLimit);
				constraints.add(cons);
			}
		}

		double objValue = runSolver(1);
		return objValue;
	}

	@Override
	protected void getFuncsToBeOffloaded(double[] ILPSolution) {
	//	for(Function func : funcMap.keySet()){
		for(Function func : funcIDMap.values()){
		//	SFunction extFunc = funcMap.get(func);
			SFunction extFunc = (SFunction)func;
			if(ILPSolution[extFunc.getSolverVar(R).id] == 1){
				toBeOffloaded.add(func);
			}
		}
	}

}
