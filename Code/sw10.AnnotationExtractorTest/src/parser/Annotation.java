package parser;

public class Annotation {
		
	private final AnnotationType annoType;
	private final String annoValue;
	
	public static enum AnnotationType {
		LOOPBOUND,
		CALLTARGET
	}
	
	public Annotation(Annotation.AnnotationType annoType, String annoValue) {
		this.annoType = annoType;
		this.annoValue = annoValue;
	}
	
	public AnnotationType getAnnotationType() {
		return this.annoType;
	}
	public String getAnnotationValue() {
		return this.annoValue;
	}
	
}
