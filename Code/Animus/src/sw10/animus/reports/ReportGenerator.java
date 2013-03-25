package sw10.animus.reports;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;

import sw10.animus.analysis.AnalysisResults;
import sw10.animus.analysis.CostResultMemory;
import sw10.animus.analysis.ICostResult;
import sw10.animus.build.AnalysisEnvironment;
import sw10.animus.build.JVMModel;
import sw10.animus.program.AnalysisSpecification;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.examples.properties.WalaExamplesProperties;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.viz.DotUtil;
import com.ibm.wala.viz.NodeDecorator;

public class ReportGenerator {

	private AnalysisSpecification specification;
	private AnalysisEnvironment environment;
	private AnalysisResults analysisResults;
	private JVMModel jvmModel;
	
	private final String RESOURCES = "resources";
	private final String DT = "dt";
	private final String PDF = "pdf";
	private final String INDEX_HTML = "index.html";
	private final String VISUALIZATION_JS = "visualization.js";
	private final String CALL_GRAPH = "callGraph";
	
	private String OUTPUT_DIR;
	private String RESOURCES_DIR;
	private String DT_DIR;
	private String PDF_DIR;
	
	public ReportGenerator() throws IOException {
		this.specification = AnalysisSpecification.getAnalysisSpecification();
		this.environment = AnalysisEnvironment.getAnalysisEnvironment();
		this.analysisResults = AnalysisResults.getAnalysisResults();
		this.jvmModel = specification.getJvmModel();
		
		String outputDir = specification.getOutputDir();
		this.OUTPUT_DIR = outputDir;
		this.RESOURCES_DIR = outputDir + File.separatorChar + RESOURCES;
		this.DT_DIR = outputDir + File.separatorChar + RESOURCES + File.separatorChar + DT;
		this.PDF_DIR = outputDir + File.separatorChar + RESOURCES + File.separatorChar + PDF;
	}
	
	public void Generate(ArrayList<ReportEntry> reportEntries) throws IOException {
		createOutputDirectories();

		VelocityEngine ve = new VelocityEngine();
		ve.init();
        Template index = ve.getTemplate("templates/index.vm");
        Template visualization = ve.getTemplate("templates/visualization.vm");
        VelocityContext ctxIndex = new VelocityContext();
        VelocityContext ctxVisualization = new VelocityContext();
        
        /* CSS and JS */
        String webDir = new File(".").getCanonicalPath() + "/web/";
        GenerateCSSIncludes(ctxIndex, webDir);
        GenerateJSIncludes(ctxIndex, webDir);
        
        /* Pages */
        GenerateSummary(ctxIndex);
        GenerateCallgraph(visualization, ctxVisualization, ctxIndex, VISUALIZATION_JS);
        GenerateDetails(ctxIndex, reportEntries);
        
        try {
        	writeTemplateToFile(index, ctxIndex, INDEX_HTML);
        } catch(IOException e) {
        	System.err.println("Could not generate output file from template, index.vm");
        } 
	}
	
	private void writeTemplateToFile(Template template, Context ctx, String fileName) throws IOException {
		StringWriter writer = new StringWriter();
		template.merge(ctx, writer);
        String filecontent = writer.toString();

        File htmlFile = new File(OUTPUT_DIR + File.separatorChar + fileName);
        if(!htmlFile.exists()){
        	htmlFile.createNewFile();
        }

        FileWriter fw = new FileWriter(htmlFile);
        fw.write(filecontent);
        fw.close();
	}
	
	private void GenerateCSSIncludes(Context ctxIndex, String webDir) {
		ctxIndex.put("bootstrapCSS", webDir + "bootstrap/css/bootstrap.css");
		ctxIndex.put("fancyboxCSS", webDir + "fancyapps-fancyBox-0ffc358/source/jquery.fancybox.css");
		ctxIndex.put("syntaxCSS", webDir + "syntaxhighlighter_3.0.83/styles/shCoreDefault.css");
		ctxIndex.put("stylesCSS", webDir + "styles.css");
	}
    
	private void GenerateJSIncludes(Context ctxIndex, String webDir) {
		ctxIndex.put("syntaxcoreJS", webDir + "syntaxhighlighter_3.0.83/scripts/shCore.js");
		ctxIndex.put("syntaxbrushJS", webDir + "syntaxhighlighter_3.0.83/scripts/shBrushJava.js");
		ctxIndex.put("fancyboxJS", webDir + "fancyapps-fancyBox-0ffc358/source/jquery.fancybox.pack.js");
		ctxIndex.put("bootstrapJS", webDir + "bootstrap/js/bootstrap.js");
		ctxIndex.put("arborJS", webDir + "arbor-v0.92/lib/arbor.js");
		ctxIndex.put("visualizationJS", OUTPUT_DIR + File.separatorChar + VISUALIZATION_JS);
		ctxIndex.put("scriptsJS", webDir + "scripts.js");
	}
	
	private void GenerateSummary(VelocityContext ctxIndex) {
		ctxIndex.put("application", "name");
        ctxIndex.put("classes", "number");
        ctxIndex.put("methods", "number");
	}
	
	private LinkedList<CGNode> entries;
	private ArrayList<CGNode> affectedWorstCaseReferencedMethodsCGNodes;
	private ArrayList<CGNode> affectedWorstCaseCallStack;
	private ArrayList<CGNode> nodesAdded;
	private CallGraph callGraph;
	private void GenerateCallgraph(Template visualization, VelocityContext ctxVisualization, VelocityContext ctxIndex, String fileName) {
		callGraph = environment.getCallGraph();
		
		try {
			GenerateCG(callGraph);
		} catch (WalaException e) {
			System.err.println("Could not generate callgraph");
		}
		
		entries = specification.getEntryPointCGNodes();
			
		/* Collect all affected worst-case referenced/Stack CGNodes for every entry point */
		affectedWorstCaseReferencedMethodsCGNodes = new ArrayList<CGNode>();
		affectedWorstCaseCallStack = new ArrayList<CGNode>();
		for(CGNode cgNode : entries) {
			CostResultMemory cost = (CostResultMemory)analysisResults.getResultsForNode(cgNode);
			ArrayList<CGNode> wcRefCGNodes = cost.worstcaseReferencesMethods;
			ArrayList<CGNode> wcStackCGNodes = AnalysisResults.getAnalysisResults().getWorstCaseStackTraceFromNode(cgNode);
			for(CGNode wcRefCGNode : wcRefCGNodes) {
				if(!affectedWorstCaseReferencedMethodsCGNodes.contains(wcRefCGNode)) {
					affectedWorstCaseReferencedMethodsCGNodes.add(wcRefCGNode);
				}
			}
			for(CGNode wcStackCGNode : wcStackCGNodes) {
				if(!affectedWorstCaseCallStack.contains(wcStackCGNode)) {
					affectedWorstCaseCallStack.add(wcStackCGNode);
				}
			}
		}
		
		nodesAdded = new ArrayList<CGNode>();
		
		StringBuilder graph  = new StringBuilder();
		StringBuilder nodes  = new StringBuilder();
		
		/* Constructing graph */
		for(CGNode cgNode : Iterator2Iterable.make(callGraph.iterator())) {
			addNode(nodes, graph, cgNode);
		}
		
		ctxVisualization.put("nodes", nodes.toString());
		ctxVisualization.put("graph", graph.toString());
		
		try {
			writeTemplateToFile(visualization, ctxVisualization, fileName);
		} catch (IOException e) {
			System.err.println("Could not generate output file from template, index.vm");
		}
	}
	
	private void addNode(StringBuilder nodes, StringBuilder graph, CGNode cgNode) {
		if(!nodesAdded.contains(cgNode)) {
			int id = cgNode.getGraphNodeId();
			IMethod method = cgNode.getMethod();
			String classLoader = method.getDeclaringClass().getClassLoader().getName().toString();
			
			String signature = method.getSignature();
			
			StringBuilder nodeObject = new StringBuilder();
			StringBuilder nodeObjectSuccs = new StringBuilder();
			
			
			IntIterator intIterator = callGraph.getSuccNodeNumbers(cgNode).intIterator();
			boolean isExpandable = (intIterator.hasNext() ? true : false);
			while(intIterator.hasNext()) {
				nodeObjectSuccs.append(intIterator.next());
				if(intIterator.hasNext()) {
					nodeObjectSuccs.append(", ");
				}
			}
			
			String innerLabel = "";
			boolean inReferences = affectedWorstCaseReferencedMethodsCGNodes.contains(cgNode);
			boolean inStack = affectedWorstCaseCallStack.contains(cgNode);
			
			if(inReferences || inStack) {
				if(inReferences && inStack) {
					innerLabel = "X";
				} else if(inReferences) {
					innerLabel = "A";
				} else {
					innerLabel = "S";
				}
			}
						
			boolean isEntry = false;
			if(entries.contains(cgNode)) {
				isEntry = true;
				innerLabel = "X";
			}
			
			nodeObject.append("{id:" + id + ", outerLabel:'" + signature + "', innerLabel:'" + innerLabel + "', entry:" + isEntry + ", classloader:'" + classLoader + "', successors:[" + nodeObjectSuccs + "]}");
			nodes.append("_this.nodes[" + id + "] = " + nodeObject + "\n");
			
			if(isEntry) {
				graph.append("sys.addNode(" + id +  ", {mass:1.0, outerLabel:'" + signature + "', innerLabel:'" + innerLabel + "', entry:true, classloader:'" + classLoader + "', expanded:false, expandable:" + isExpandable + "})\n");
			}
			
			nodesAdded.add(cgNode);
		}
	}
	
	private void GenerateDetails(VelocityContext ctxIndex, ArrayList<ReportEntry> reportEntries) throws IOException {
		
		BufferedReader fileJavaReader;
		
		StringBuilder sidemenuAllocations = new StringBuilder();
		StringBuilder sidemenuJVMStack = new StringBuilder();
		StringBuilder code = new StringBuilder();
		StringBuilder jvmStack = new StringBuilder();
		
		StringBuilder lines;
		
		for(ReportEntry reportEntry : reportEntries) {
			String javaFile = reportEntry.getSource();
			for(Entry<CGNode, ICostResult> entry : reportEntry.getEntries().entrySet()) {
				CGNode cgNode = entry.getKey();
				ICostResult cost = entry.getValue();
				Set<Integer> lineNumbers = reportEntry.getLineNumbers(cgNode);
				String packages = reportEntry.getPackage();
				if(packages.equals(""))
					packages = "default";
								
				IMethod method = cgNode.getMethod();				
				String guid = java.util.UUID.randomUUID().toString();
				
				CostResultMemory memCost = (CostResultMemory)cost;
				
				/* Control-Flow Graph */
				try {
					GenerateCFG(cgNode.getIR().getControlFlowGraph(), guid);
				}catch(WalaException e) {
					System.err.println("Could not generate report: " + e.getMessage());
					continue;
				}
				
				/* JVMStack side menu */
				sidemenuJVMStack.append("<li><a title=\"" + method.getSignature() + "\" id=\"methodjvm-" + guid + "\" href=\"#\"><i class=\"icon-home icon-black\"></i>" + method.getSignature() + "</a></li>\n");
				sidemenuJVMStack.append("<ul class=\"nav nav-list\">");
				sidemenuJVMStack.append("<li><i class=\"icon-certificate icon-black\"></i>Cost: " + memCost.getAccumStackCost() + "</li>\n");
				sidemenuJVMStack.append("</ul>");
				
				/* Allocations side menu */
				sidemenuAllocations.append("<li><a title=\"" + method.getSignature() + "\" id=\"method-" + guid + "\" href=\"#\"><i class=\"icon-home icon-black\"></i>" + method.getSignature() + "</a></li>\n");
				
				/* Sub-menu level 1  */
				sidemenuAllocations.append("<ul class=\"nav nav-list\">");
				sidemenuAllocations.append("<li><i class=\"icon-certificate icon-black\"></i>Cost: " + cost.getCostScalar() + "</li>\n");
				String href = PDF_DIR + File.separatorChar + guid + ".pdf";
				sidemenuAllocations.append("<li><a data-fancybox-type=\"iframe\" class=\"cfgViewer\" href=\"" + href + "\"><i class=\"icon-refresh icon-black\"></i>Control-Flow Graph</a></li>\n");
				href = guid;
				
				sidemenuAllocations.append("<li><a id=\"details-" + guid + "\" href=\"#\"><i class=\"icon-search icon-black\"></i>Details</a></li>\n");
				sidemenuAllocations.append("<li><a id=\"referencedMethods-" + guid + "\" href=\"#\"><i class=\"icon-align-justify icon-black\"></i>Referenced Methods</a></li>\n");
				sidemenuAllocations.append("<ul id=\"methodrefsub-" + guid + "\" class=\"nav nav-list\" style=\"display:none;\">");
				
				Map<CGNode, String> guidByRefMethod = new HashMap<CGNode, String>();
				for(CGNode refCGNode : memCost.worstcaseReferencesMethods) {
					IMethod refMethod = refCGNode.getMethod();
					String refMethodSignature = refMethod.getSignature();
					if(refMethodSignature.contains("<")) {
						refMethodSignature = refMethodSignature.replace("<", "&lt;");
						refMethodSignature = refMethodSignature.replace(">", "&gt;");
					}
					
					String refMethodGuid = java.util.UUID.randomUUID().toString();
					sidemenuAllocations.append("<li><a id=\"methodrefsubentry-" + refMethodGuid + "\" href=\"#\"><i class=\"icon-arrow-right icon-black\"></i>" + refMethodSignature + "</a></li>\n");
					guidByRefMethod.put(refCGNode, refMethodGuid);
				}	
				
				sidemenuAllocations.append("</ul>\n");
				sidemenuAllocations.append("</ul>\n");

				lines = new StringBuilder();
				Iterator<Integer> linesIterator = lineNumbers.iterator();
				while(linesIterator.hasNext()) {
					lines.append(linesIterator.next());
					if(linesIterator.hasNext())
						lines.append(", ");
				}
				
				fileJavaReader = new BufferedReader(new FileReader(javaFile));
				
				/* Code section */
				code.append("<div id=\"code-" + guid + "\">\n");
				code.append("<pre class=\"brush: java; highlight: [" + lines + "]\">\n&nbsp;");
				
				String line;
		        while ((line = fileJavaReader.readLine()) != null) {
		        	code.append(line + "\n");
		        }
		        code.append("</pre>");
				code.append("</div>");
				
				/* Stack div */
				ArrayList<CGNode> callStack = AnalysisResults.getAnalysisResults().getWorstCaseStackTraceFromNode(cgNode);
				jvmStack.append("<div id=\"stack-" + guid + "\" style=\"display:none; width:80%;\">");
				for(CGNode stackElement : callStack) {
					IMethod stackElementImethod = stackElement.getMethod();
					String stackGuid = java.util.UUID.randomUUID().toString();
					CostResultMemory stackElementCost = (CostResultMemory)AnalysisResults.getAnalysisResults().getResultsForNode(stackElement);
					int locals = stackElementCost.getMaxLocals();
					int stack = stackElementCost.getMaxStackHeight();
					
					StringBuilder content = new StringBuilder();
					content.append("<small>Max Locals:       " + locals + "</small><br/>");
					content.append("<small>Max Stack height: " + stack + "</small>");
					
					jvmStack.append("<a style=\"text-decoration:none; color:#000000\" data-html=\"true\" data-trigger=\"manual\" id=\"stackelement-" + stackGuid + "\" rel=\"popover\" data-content=\"" + content + "\" data-original-title=\"Accumulated: " + stackElementCost.getAccumStackCost() + "\">");
					jvmStack.append("<div style=\"height:90px; margin:0px; text-align:center;\" class=\"well\"><div style=\"margin-top:35px;\">" + stackElement.getMethod().getSignature() + "</div></div>");
					jvmStack.append("</a>");
				}
				
				jvmStack.append("</div>");
				
				/* Details div */
				code.append("<div class=\"well\" id=\"det-" + guid + "\" style=\"display:none; position:relative;\">\n");
				code.append("<h3>" + method.getSignature() + "</h3>");
				code.append("<span class=\"label label-info topRight\">Cost: " + memCost.getCostScalar() + "</span>");
				
				if(memCost.countByTypename.size() > 0) {
					/* Allocations table (self) */
					code.append("<br/><br/><div class=\"desc\">Allocation table for the method itself</div>");
					code.append("<table class=\"table table-striped table-bordered table-hover\">");
					code.append("<tbody>");
					code.append("<tr>");
					code.append("<td width=\"60%\"><b>Typename</b></td>");
					code.append("<td width=\"20%\"><b>Count</b></td>");
					code.append("<td width=\"20%\"><b>Cost</b></td>");
					code.append("</tr>");
					for(Entry<TypeName, Integer> countByTypename : memCost.countByTypename.entrySet()) {
						code.append("<tr>");
						TypeName typeName = countByTypename.getKey();
						code.append("<td>" + typeName + "</td>");
						int count = countByTypename.getValue();
						code.append("<td>" + count + "</td>");
						int typeSize = jvmModel.getSizeForQualifiedType(typeName);
						code.append("<td>" + count*typeSize + "</td>");
						code.append("</tr>");
					}
					code.append("</tbody>");
					code.append("</table>");
				}
				
				if(memCost.aggregatedCountByTypename.size() > 0) {
					/* Allocations table (aggr) */
					code.append("<div class=\"desc\">Aggregrated allocation table for all referenced methods and the method itself</div>");
					code.append("<table class=\"table table-striped table-bordered table-hover\">");
					code.append("<tbody>");
					code.append("<tr>");
					code.append("<td width=\"60%\"><b>Typename</b></td>");
					code.append("<td width=\"20%\"><b>Count</b></td>");
					code.append("<td width=\"20%\"><b>Cost</b></td>");
					code.append("</tr>");
					for(Entry<TypeName, Integer> countByTypename : memCost.aggregatedCountByTypename.entrySet()) {
						code.append("<tr>");
						TypeName typeName = countByTypename.getKey();
						code.append("<td>" + typeName + "</td>");
						int count = countByTypename.getValue();
						code.append("<td>" + count + "</td>");
						int typeSize = jvmModel.getSizeForQualifiedType(typeName);
						code.append("<td>" + count*typeSize + "</td>");
						code.append("</tr>");
					}
					code.append("</tbody>");
					code.append("</table>");
				}
				
				code.append("</div>");
				
				/* Referenced Method div */
				code.append("<div id=\"ref-" + guid + "\" style=\"display:none;\">\n");
				for(CGNode refCGNode : memCost.worstcaseReferencesMethods) {	
					IMethod refMethod = refCGNode.getMethod();
					String refMethodSignature = refMethod.getSignature();
					if(refMethodSignature.contains("<")) {
						refMethodSignature = refMethodSignature.replace("<", "&lt;");
						refMethodSignature = refMethodSignature.replace(">", "&gt;");
					}
					
					code.append("<div class=\"well\" style=\"position:relative;\">");
					code.append("<h3>" + refMethodSignature + "</h3>");
					CostResultMemory refCGNodeCost = (CostResultMemory)analysisResults.getResultsForNode(refCGNode);
					code.append("<span class=\"label label-info topRight\">Cost: " + refCGNodeCost.getCostScalar() + "</span>");
					
					if(refCGNodeCost.aggregatedCountByTypename.size() > 0) {
						/* Allocations table (aggr) */
						code.append("<br/><div class=\"desc\">Aggregrated allocation table for all referenced methods and the method itself</div>");
						code.append("<table class=\"table table-striped table-bordered table-hover\">");
						code.append("<tbody>");
						code.append("<tr>");
						code.append("<td width=\"60%\"><b>Typename</b></td>");
						code.append("<td width=\"20%\"><b>Count</b></td>");
						code.append("<td width=\"20%\"><b>Cost</b></td>");
						code.append("</tr>");
						for(Entry<TypeName, Integer> countByTypename : memCost.aggregatedCountByTypename.entrySet()) {
							code.append("<tr>");
							TypeName typeName = countByTypename.getKey();
							code.append("<td>" + typeName + "</td>");
							int count = countByTypename.getValue();
							code.append("<td>" + count + "</td>");
							int typeSize = jvmModel.getSizeForQualifiedType(typeName);
							code.append("<td>" + count*typeSize + "</td>");
							code.append("</tr>");
						}
						code.append("</tbody>");
						code.append("</table>");
					}
					code.append("</tbody>");
					code.append("</table>");
					code.append("</div>");
				}
				code.append("</div>");
			}
		}
		
		
		ctxIndex.put("sidemenuAllocations", sidemenuAllocations.toString());
		ctxIndex.put("sidemenuJVMStack", sidemenuJVMStack.toString());
		ctxIndex.put("code", code.toString());
		ctxIndex.put("JVMStack", jvmStack.toString());
	}
	
	private void GenerateCFG(SSACFG cfg, String guid) throws WalaException{
		Properties wp = WalaProperties.loadProperties();
	    wp.putAll(WalaExamplesProperties.loadProperties());

	    String psFile = PDF_DIR + File.separatorChar + guid + ".pdf";	
	    String dotFile = DT_DIR + File.separatorChar + guid + ".dt";
	    String dotExe = wp.getProperty(WalaExamplesProperties.DOT_EXE);
	    
	    final HashMap<BasicBlock, String> labelMap = HashMapFactory.make();
	    for (Iterator<ISSABasicBlock> iteratorBasicBlock = cfg.iterator(); iteratorBasicBlock.hasNext();) {
	        SSACFG.BasicBlock basicBlock = (SSACFG.BasicBlock) iteratorBasicBlock.next();
	        
	        StringBuilder label = new StringBuilder();
	        label.append(basicBlock.toString() + "\n");
	        
	        if(basicBlock.isEntryBlock())
	        	label.append("(entry)");
	        else if(basicBlock.isExitBlock())
	        	label.append("(exit)");
	        
	        Iterator<SSAInstruction> iteratorInstruction = basicBlock.iterator();
	        while(iteratorInstruction.hasNext()) {
	        	SSAInstruction inst = iteratorInstruction.next();
	        	label.append(inst.toString() + "\n");
	        }
	        
	        labelMap.put(basicBlock, label.toString());
	    }
	    NodeDecorator labels = new NodeDecorator() {
	        public String getLabel(Object o) {
	            return labelMap.get(o);
	        }
	    };
		DotUtil.dotify(cfg, labels, dotFile, psFile, dotExe);
	}
	
	private void GenerateCG(CallGraph callGraph) throws WalaException{
		Properties wp = WalaProperties.loadProperties();
	    wp.putAll(WalaExamplesProperties.loadProperties());

	    String psFile = PDF_DIR + File.separatorChar + CALL_GRAPH  + ".pdf";	
	    String dotFile = DT_DIR + File.separatorChar + CALL_GRAPH + ".dt";
	    String dotExe = wp.getProperty(WalaExamplesProperties.DOT_EXE);
	    
	    final HashMap<CGNode, String> labelMap = HashMapFactory.make();
	    for (Iterator<CGNode> iteratorCallGraph = callGraph.iterator(); iteratorCallGraph.hasNext();) {
	        CGNode cgNode = iteratorCallGraph.next();
	        
	        StringBuilder label = new StringBuilder();
	        label.append(cgNode.toString());
	        labelMap.put(cgNode, label.toString());
	    }
	    
	    NodeDecorator labels = new NodeDecorator() {
	        public String getLabel(Object o) {
	            return labelMap.get(o);
	        }
	    };
		DotUtil.dotify(callGraph, labels, dotFile, psFile, dotExe);
	}
	
	private void createOutputDirectories() {
		File outputDir = new File(OUTPUT_DIR);
		if(!outputDir.exists()) {
			try {
				outputDir.mkdir();
				new File(RESOURCES_DIR).mkdir();
				new File(DT_DIR).mkdir();
				new File(PDF_DIR).mkdir();
			} catch (SecurityException e) {
				System.err.println("Could not create output directories");
				e.printStackTrace();
			}
		} else {	
		    File dtDir = new File(DT_DIR);
		    File pdfDir = new File(PDF_DIR);
		    
		    File[] files = dtDir.listFiles();
		    for(File file : files) {
		    	file.delete();
		    }
		    files = pdfDir.listFiles();
		    for(File file : files) {
		    	file.delete();
		    }
		    
		}
	}
}
