package uppaal;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jdom.Element;

import uppaal.labels.Comment;
import uppaal.labels.Invariant;

public class Location extends PositionedUppaalElement{

	private boolean transitional;
	private static final Pattern locationIdRegExPattern = Pattern.compile("\\d+");

	// testing accepting locations
	private boolean accept; 
	public boolean isAccept() {
		return accept;
	}
	public void setAccept(boolean accept) {
		this.accept = accept;
		if(accept){
			getComment().addCommentLine("ACCEPT");
		} else
			setComment("");
	}

	public void setComment(String string) {
		this.setComment(new Comment(string));	
	}

	/**
	 * The different types of locations
	 */
	public enum LocationType { COMMITTED, NORMAL, URGENT }
	
	private static int idcounter = 0;
	protected static int getNewID(){
		return idcounter++;
	}
	
	private Automaton automaton;
	private Comment comment;
	private int id = getNewID();
	private Invariant invariant;
	private Name name;
	private LocationType type;
	
	public Location(Automaton automaton) {
		this(automaton, null, null, 0,0);
	}
	
	public Location(Automaton automaton, Element locationElement) {
		super(locationElement); // get coordinates
		type = getLocationType(locationElement);
		@SuppressWarnings("unchecked")
		List<Element> children = locationElement.getChildren();
		for(Element child: children){
			if(child.getName().equals("name"))
				name = new Name(child);
			if(child.getName().equals("comment"))
				comment = new Comment(child);
			if(child.getName().equals("label")){
				if("invariant".equals(child.getAttributeValue("kind"))){
					invariant=new Invariant(child);
				}
			}
		}
		String idString = locationElement.getAttributeValue("id");
		
		Matcher regExMatcher = locationIdRegExPattern.matcher(idString);
		boolean dbg_matchresult = regExMatcher.find();
		assert dbg_matchresult;

		String idMatch = regExMatcher.group();

		this.id=Integer.parseInt(idMatch);

		automaton.addLocation(this);
		this.setAutomaton(automaton);
	}

	
	/**
	 * Creates a new location with all the properties set.
	 * The name and id must be unique in the template, which is not checked here
	 * @param name The name of the location
	 * @param type The type of the location
	 * @param x The x position of the Location
	 * @param y The y position of the Location
	 */
	public Location(Automaton automaton, Name name, LocationType type, int x, int y){
		super(x,y);
		this.name = name;
		this.comment = new Comment();
		this.invariant = new Invariant(x, y);
		automaton.addLocation(this);
		this.setAutomaton(automaton);
	}
	
	/**
	 * Creates a new location with the given name and id
	 * The name and id must be unique in the template, which is not checked here
	 * @param name The name of the location
	 * @param id The id of the location
	 */
	public Location(Automaton automaton, String name){
		this(automaton, new Name(name), LocationType.NORMAL, 0, 0);
	}
	
	@Override
	public Element generateXMLElement() {
		Element result = super.generateXMLElement();
		result.setAttribute("id", getUniqueIdString());
		
		if(getName()!=null)
			result.addContent(getName().generateXMLElement());
		
		if(getComment()!=null)
			result.addContent(getComment().generateXMLElement());
		if(getInvariant()!=null)
			result.addContent(getInvariant().generateXMLElement());

		if(type!=null){
			switch (this.type) {
			case COMMITTED:
				result.addContent(new Element("committed"));
				break;
	
			case URGENT:
				result.addContent(new Element("urgent"));
				break;
			default:
				break;
			}
		}		
		return result;
	}
	
	public Automaton getAutomaton() {
		return automaton;
	}
	
	public Comment getComment(){
		return comment;
	}

	public int getId() {
		return id;
	}

	public String getUniqueIdString(){
		return automaton.getName().getName()+id;
	}

	public Invariant getInvariant() {
		return invariant;
	}

	private LocationType getLocationType(Element xmlRepresentation){
		if(xmlRepresentation.getChild("urgent") != null)
			return LocationType.URGENT;
		if(xmlRepresentation.getChild("committed") != null)
			return LocationType.COMMITTED;
		return LocationType.NORMAL;
	}

	public Name getName() {
		return name;
	}

	public LocationType getType() {
		return type;
	}

	@Override
	public String getXMLElementName() {
		return "location";
	}

	public void setAutomaton(Automaton automaton) {
		this.automaton = automaton;
	}

	@Override
	public void setPositioned(boolean positioned) {
		super.setPositioned(positioned);
		if(invariant != null)
			invariant.setPositioned(positioned);
		if(name != null)
			name.setPositioned(positioned);
	}

	public void setComment(Comment comment) {
		this.comment = comment;
	}

	public void setInvariant(Invariant invariant) {
		this.invariant = (invariant);
	}

	public void setName(Name name) {
		this.name = name;
	}
	
	public void setPosX(int posX) {
		int diff = posX - getPosX();
		this.name.setPosX(name.getPosX()+diff);
		this.invariant.setPosX(invariant.getPosX()+diff);
		super.setPosX(posX);
	}

	public void setPosY(int posY) {
		int diff = posY - getPosY();
		this.name.setPosY(name.getPosY()+diff);
		this.invariant.setPosY(invariant.getPosY()+diff);
		super.setPosY(posY);
	}
	
	public void setType(LocationType type) {
		this.type = type;
	}

	@Override
	public String toString(){
		return name.toString();
	}

	public void setName(String name) {
		this.setName(new Name(name));
	}

	public void setInvariant(String invariantString) {
		this.setInvariant(new Invariant(invariantString));
	}
	
	public List<Location> getSuccessors(){
		return automaton.getSuccs(this);
	}
	public List<Location> getPredecessors(){
		return automaton.getPreds(this);
	}
	public boolean isBranching(){
		return automaton.isBranching(this);
	}
	public boolean isMerging(){
		return automaton.isMerging(this);
	}
	
	public List<Transition> getOutgoingTransitions(){
		return automaton.getOutgoingTransitions(this);
	}
	
	public List<Transition> getIncommingTransitions(){
		return automaton.getIncommingTransitions(this);
	}

	public void setTransitional(boolean transitional) {
		this.transitional = transitional;
	}

	public boolean isTransitional() {
		return transitional;
	}

	public Transition linkTo(Location destination) {
		Transition t = new Transition(automaton, this, destination);
		return t;
	}
	
	public List<Transition> linkTo(List<Location> destinations){
		List<Transition> transitions = new LinkedList<Transition>();
		for(Location dest: destinations){
			transitions.add(this.linkTo(dest));
		}
		return transitions;
	}
}
