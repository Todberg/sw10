package parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import parser.Annotation.AnnotationType;

public class Parser {

	private static final String ANNOTATION_FORMAT = "//@";
	private static final String ANNOTATION_REGEX = "[" + ANNOTATION_FORMAT + "][\\s]*([a-zA-Z]*)[\\s]*[\\s]*[=][\\s]*([\\-_a-zA-Z0-9]+)";
	
	private static final Pattern annotationRegex = Pattern.compile(ANNOTATION_REGEX);
	
	private static final int ANNOTATION_TYPE_GROUP = 1;
	private static final int ANNOTATION_VALUE_GROUP = 2;
	
	
	public Map<Integer, Annotation> GetAnnotations(BufferedReader fileReader) throws IOException {
		Matcher regexMatcher;

		Map<Integer, Annotation> annotations = new HashMap<Integer, Annotation>();
		
		String annotationType;
		String annotationValue;

		String line = "";
		int lineNumber = 1;
		
		while((line = fileReader.readLine()) != null) {
			if(line.contains(ANNOTATION_FORMAT)) {
				regexMatcher = annotationRegex.matcher(line);
				
				if(regexMatcher.find()) {
					annotationType = regexMatcher.group(ANNOTATION_TYPE_GROUP);
					annotationValue = regexMatcher.group(ANNOTATION_VALUE_GROUP);
					if(annotationType.equals("loopbound")) {
						annotations.put(lineNumber, new Annotation(AnnotationType.LOOPBOUND, annotationValue));				
					}
				}
			}
			lineNumber++;
		}
		return annotations;
	}
}
