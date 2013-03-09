package sw10.animus.analysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lpsolve.LpSolve;
import lpsolve.LpSolveException;
import sw10.animus.analysis.loopanalysis.CFGLoopAnalyzer;
import sw10.animus.build.AnalysisEnvironment;
import sw10.animus.program.AnalysisSpecification;
import sw10.animus.util.LpFileCreator;
import sw10.animus.util.LpFileCreator.ObjectiveFunction;
import sw10.animus.util.Util;
import sw10.animus.util.annotationextractor.extractor.AnnotationExtractor;
import sw10.animus.util.annotationextractor.parser.Annotation;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.labeled.SlowSparseNumberedLabeledGraph;
import com.ibm.wala.util.graph.traverse.BFSIterator;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

public class Analyzer {

	private AnalysisSpecification specification;
	private AnnotationExtractor extractor;
	private Class<? extends ICostComputer<ICostResult>> costComputerType;
	private ICostComputer<ICostResult> costComputer;
	private AnalysisEnvironment environment;
	private AnalysisResults results;

	private Analyzer(AnalysisSpecification specification, AnalysisEnvironment environment) {
		this.environment = environment;
		this.specification = specification;
		this.extractor = new AnnotationExtractor();		
		this.results = AnalysisResults.getAnalysisResults();
	}

	public static Analyzer makeAnalyzer(AnalysisSpecification specification, AnalysisEnvironment environment) {
		return new Analyzer(specification, environment);
	}
	
	public void start(Class<? extends ICostComputer<ICostResult>> costComputerType) throws InstantiationException, IllegalAccessException, IllegalArgumentException, WalaException, IOException {
		this.costComputerType = costComputerType;
		costComputer = this.costComputerType.newInstance();
		CGNode entryNode = environment.callGraph.getEntrypointNodes().iterator().next();
		ICostResult results = analyzeNode(entryNode);
		System.out.println("Worst case allocation:" + results.getCostScalar());
	}

	public ICostResult analyzeNode(CGNode cgNode) {
		IMethod method = cgNode.getMethod();
		
		System.out.println(method.toString());
		IR ir = cgNode.getIR();

		Pair<SlowSparseNumberedLabeledGraph<ISSABasicBlock, String>, Map<String, Pair<Integer, Integer>>> sanitized = null;
		try {
			sanitized = Util.sanitize(ir, environment.classHierarchy);
		} catch (IllegalArgumentException e) {
		} catch (WalaException e) {
		}
		SlowSparseNumberedLabeledGraph<ISSABasicBlock, String> cfg = sanitized.fst;
		Map<String, Pair<Integer, Integer>> edgeLabelToNodesIDs = sanitized.snd;
		
		
		if (method.getName().toString().equals("hey")) {
			try {
				Util.CreatePDFCFG(cfg, environment.classHierarchy, cgNode);
			} catch (WalaException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		Map<Integer, Annotation> annotationByLineNumber = getAnnotations(method);
		Map<Integer, ArrayList<Integer>> loopBlocksByHeaderBlockId = getLoops(cfg, ir.getControlFlowGraph().entry());

		LpFileCreator lpFileCreator = null;
		try {
			lpFileCreator = new LpFileCreator("./", "application" + cgNode.getMethod().getName() + cgNode.getGraphNodeId() + ".lp");
		} catch (IOException e) {
		}

		lpFileCreator.setObjectiveFunction(ObjectiveFunction.MAX);

		BFSIterator<ISSABasicBlock> iteratorBFSOrdering = new BFSIterator<ISSABasicBlock>(cfg);

		ICostResult intermediateResults = null;

		while(iteratorBFSOrdering.hasNext()) {
			ISSABasicBlock currentBlock = iteratorBFSOrdering.next();
			lpFileCreator.addObjective("bb" + currentBlock.getGraphNodeId());

			Iterator<? extends String> IteratorOutgoingLabels = (Iterator<? extends String>)cfg.getSuccLabels(currentBlock);
			Iterator<? extends String> IteratorIncomingLabels = (Iterator<? extends String>)cfg.getPredLabels(currentBlock);
			List<String> outgoing = new ArrayList<String>();
			List<String> incoming = new ArrayList<String>();

			while (IteratorOutgoingLabels.hasNext()) {
				String edgeLabel = IteratorOutgoingLabels.next();
				outgoing.add(edgeLabel);
			}

			while (IteratorIncomingLabels.hasNext()) {
				String edgeLabel = IteratorIncomingLabels.next();
				incoming.add(edgeLabel);
			}

			String flowConstraint = "";
			String allocConstraint = "";
			String loopConstraint = "";
			boolean didAddLoop = false;

			if (currentBlock.isEntryBlock()) {
				flowConstraint = "f0 = 1";
				allocConstraint = "bb0 = 0 f0";
			}
			else if (currentBlock.isExitBlock()) {
    			allocConstraint += "bb" + currentBlock.getGraphNodeId() + " = ";
    			Iterator<String> IteratorIncoming = incoming.iterator();
    			while(IteratorIncoming.hasNext()) {
    				String incommingLabel = IteratorIncoming.next();
    				flowConstraint += incommingLabel;
    				allocConstraint += "0 " + incommingLabel;
    				if(IteratorIncoming.hasNext()) {
    					flowConstraint += " + ";
    					allocConstraint += " + ";
    				}
    			}
    			flowConstraint += " = 1";
			}
			else
			{
				ICostResult costForBlock = analyzeBasicBlock(currentBlock, cgNode);
				if (costForBlock != null) {
					if (intermediateResults != null) {
						costComputer.addCost(costForBlock, intermediateResults);
					}
					else
					{
						intermediateResults = costForBlock;	
					}
				}
				
	   			StringBuilder lhs = new StringBuilder(outgoing.size()*4);
    			StringBuilder rhs = new StringBuilder(incoming.size()*4);
    			StringBuilder allocRhs = new StringBuilder(incoming.size()*8);
    			StringBuilder loopLhs = new StringBuilder(incoming.size()*4);
    			StringBuilder loopRhs = new StringBuilder(outgoing.size()*4);
    			
    			int edgeIndex = 0;
    			for (String incomingLabel : incoming) {
    				lhs.append(incomingLabel);
    				if (costForBlock != null) {
    					allocRhs.append(costForBlock.getCostScalar() + " " + incomingLabel);
    				}
    				else
    				{
    					allocRhs.append("0 " + incomingLabel);
    				}
    				
    				if (edgeIndex != incoming.size() - 1) {
    					lhs.append(" + ");
    					allocRhs.append(" + ");
    				}
    				
    				edgeIndex++;
    			}
    			
    			edgeIndex = 0;
    			for (String outgoingLabel : outgoing) {
    				rhs.append(outgoingLabel);
    				
    				if (edgeIndex != outgoing.size() - 1) {
    					rhs.append(" + ");
    				}
    				
    				edgeIndex++;
    			}
    			
    			if (loopBlocksByHeaderBlockId.containsKey(currentBlock.getGraphNodeId())) {
    				didAddLoop = true;
    				ArrayList<Integer> loopBlocks = loopBlocksByHeaderBlockId.get(currentBlock.getGraphNodeId());
    				IntSet loopHeaderSuccessors = cfg.getSuccNodeNumbers(currentBlock);
    				IntSet loopHeaderAncestors = cfg.getPredNodeNumbers(currentBlock);
    				
    				int lineNumberForLoop = 0;
    				String boundForLoop = "";
					try {
						IBytecodeMethod bytecodeMethod = (IBytecodeMethod)cgNode.getMethod();
						lineNumberForLoop = bytecodeMethod.getLineNumber(bytecodeMethod.getBytecodeIndex(currentBlock.getFirstInstructionIndex()));
						if (annotationByLineNumber == null || !annotationByLineNumber.containsKey(lineNumberForLoop)) {
							System.err.println("No bound for loop detected in " + method.getName());
						} else {
							boundForLoop = annotationByLineNumber.get(lineNumberForLoop).getAnnotationValue();
						}
					} catch (InvalidClassFileException e) {
					}    	
    				
    				for(int i : loopBlocks) {
    					if (loopHeaderSuccessors.contains(i)) {
    						loopLhs.append(cfg.getEdgeLabels(currentBlock, cfg.getNode(i)).iterator().next());
    						break;
    					}
    				}
    				
    				IntIterator ancestorGraphIds = loopHeaderAncestors.intIterator();
    				while (ancestorGraphIds.hasNext()) {
    					int ancestorID = ancestorGraphIds.next();
    					if (!loopBlocks.contains(ancestorID)) {
    						loopRhs.append(boundForLoop + " " + cfg.getEdgeLabels(cfg.getNode(ancestorID), currentBlock).iterator().next());
    					}
    				}
    			}
    			
    			flowConstraint = lhs + " = " + rhs;
    			allocConstraint = "bb" + currentBlock.getGraphNodeId() + " = " + allocRhs;
    			loopConstraint = loopLhs + " = " + loopRhs;
			}
			
			lpFileCreator.addFlowContraint(flowConstraint);
    		if (didAddLoop) {
    			lpFileCreator.addLoopContraint(loopConstraint);
    			didAddLoop = false;
    		}
    		lpFileCreator.addAllocationContraint(allocConstraint);
		}
		
		LpSolve solver = null;
		try {
			lpFileCreator.writeFile();
			solver = LpSolve.readLp("application" + cgNode.getMethod().getName() + cgNode.getGraphNodeId() + ".lp", 1, null);
	    	solver.solve();
	    	System.out.println("LPSolve result for " + cgNode.getMethod().getName() + ", " + cgNode.getMethod().getDeclaringClass().getName() +  ": " + Math.round(solver.getObjective()));
		} catch (IOException e) {
			e.printStackTrace();
		} catch (LpSolveException e) {
			System.out.println("LPSolve FAILED for " + cgNode.getMethod().getName() + ", " + cgNode.getMethod().getDeclaringClass().getName());
			e.printStackTrace();
		}
		
		
		if (intermediateResults == null) {
			intermediateResults = new CostResultMemory();
		}
				
		ICostResult finalResults = costComputer.getFinalResultsFromContextResultsAndLPSolutions(intermediateResults, solver);
		results.saveResultForNode(cgNode, finalResults);

		return finalResults;
	}

	private ICostResult analyzeBasicBlock(ISSABasicBlock block, CGNode node) {
		ICostResult costForBlock = null;

		for(SSAInstruction instruction : Iterator2Iterable.make(block.iterator())) {
			ICostResult costForInstruction = analyzeInstruction(instruction, block, node);

			if (costForInstruction != null) {
				if (costForBlock != null) {
					costComputer.addCost(costForInstruction, costForBlock);
				}
				else
				{
					costForBlock = costForInstruction;
				}
			}
		}

		return costForBlock;
	}

	private ICostResult analyzeInstruction(SSAInstruction instruction, ISSABasicBlock block, CGNode node) {
		ICostResult costForInstruction = null;
		
		if(instruction instanceof SSAInvokeInstruction) {
			SSAInvokeInstruction inst = (SSAInvokeInstruction)instruction;
			if(inst.isDispatch()) {	// invokevirtual
				CallSiteReference callSiteRef = inst.getCallSite();
				Set<CGNode> possibleTargets = environment.callGraph.getPossibleTargets(node, callSiteRef);
				ICostResult maximumResult = new CostResultMemory();
				ICostResult tempResult = null;
				for(CGNode target : Iterator2Iterable.make(possibleTargets.iterator())) {
					tempResult = (results.isNodeProcessed(target) ? results.getResultsForNode(target) : analyzeNode(target));			
					if(tempResult.getCostScalar() > maximumResult.getCostScalar())
						maximumResult = tempResult;
				}
				return maximumResult;
			} else { // invokestatic or invokespecial
				MethodReference targetRef = inst.getDeclaredTarget();
				Set<CGNode> targets = environment.callGraph.getNodes(targetRef);
				CGNode target = targets.iterator().next();
				return (results.isNodeProcessed(target) ? results.getResultsForNode(target) : analyzeNode(target));
			}
		} else if(costComputer.isInstructionInteresting(instruction)) {
			costForInstruction = costComputer.getCostForInstructionInBlock(instruction, block, node);
		}

		return costForInstruction;
	}

	private Map<Integer, ArrayList<Integer>> getLoops(Graph<ISSABasicBlock> graph, ISSABasicBlock entry) {
		CFGLoopAnalyzer loopAnalyzer = CFGLoopAnalyzer.makeAnalyzerForCFG(graph);
		loopAnalyzer.runDfsOrdering(entry);

		return loopAnalyzer.getLoopHeaderBasicBlocksGraphIds();
	}

	private Map<Integer, Annotation> getAnnotations(IMethod method) {
		IClass declaringClass = method.getDeclaringClass();
		String packageName = declaringClass.getName().toString();
		packageName = Util.getClassNameOrOuterMostClassNameIfNestedClass(packageName);
		packageName = (packageName.contains("/") ? packageName.substring(1, packageName.lastIndexOf('/')) : "");

		String path = specification.getSourceFilesRootDir() + '/';
		path = (packageName.isEmpty() ? path : path + packageName + '/');

		String sourceFileName = declaringClass.getSourceFileName();
		Map<Integer, Annotation> annotationsForMethod = null;
		try {
			annotationsForMethod = extractor.retrieveAnnotations(path, sourceFileName);
		} catch (IOException e) {
			e.printStackTrace();
		}

		return annotationsForMethod;
	}
}

	