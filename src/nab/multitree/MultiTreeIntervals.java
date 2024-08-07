package nab.multitree;



import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import beast.core.CalculationNode;
import beast.core.Description;
import beast.core.Input;
import beast.core.Input.Validate;
import beast.core.parameter.RealParameter;
import beast.core.util.Log;
import beast.evolution.tree.Node;
import beast.evolution.tree.TraitSet;
import beast.evolution.tree.Tree;
import beast.evolution.tree.coalescent.IntervalList;
import beast.evolution.tree.coalescent.IntervalType;
import beast.util.HeapSort;


/**
 * Extracts the intervals from a beast.tree.
 *
 * @author Andrew Rambaut
 * @author Alexei Drummond
 * @version $Id: TreeIntervals.java,v 1.9 2005/05/24 20:25:56 rambaut Exp $
 */
@Description("Extracts the intervals from a tree. Points in the intervals " +
        "are defined by the heights of nodes in the tree.")
public class MultiTreeIntervals extends CalculationNode implements IntervalList {
	
    final public Input<List<Tree>> treeInput = new Input<>("tree", "tree for which to calculate the intervals", new ArrayList<>());
//    final public Input<List<RealParameter>> offsetInput = new Input<>("offset", "time offset ", new ArrayList<>());
    final public Input<List<RealParameter>> rootLengthInput = new Input<>("rootLength", "time offset ", new ArrayList<>());

    double[] offset;

    
    public MultiTreeIntervals() {
        super();
    }

    public MultiTreeIntervals(List<Tree> treeList) {
    	for (Tree t : treeList)
    		init(t);
    }

    @Override
    public void initAndValidate() {
    	offset = new double[treeInput.get().size()];
    	for (int i = 0; i < treeInput.get().size(); i++) {
    		offset[i] = getMaxValue(treeInput.get().get(i).getDateTrait());
    	}  
    	
    	double maxVal = 0;
    	for (double v:offset)
    		maxVal = Math.max(maxVal, v);
    	
    	for (int i = 0; i < offset.length; i++) {
    		offset[i] = Math.abs(offset[i] - maxVal);
    	}

        // this initialises data structures that store/restore might need
        calculateIntervals();
        intervalsKnown = false;
    }
    
    private double getMaxValue(TraitSet trait) {
        if (trait.traitsInput.get().matches("^\\s*$")) {
            return -1.0;
        }

        // first, determine taxon numbers associated with traits
        // The Taxon number is the index in the alignment, and
        // used as node number in a tree.
        Map<String, Integer> map = new HashMap<>();
        List<String> labels = trait.taxaInput.get().asStringList();
        String[] traits = trait.traitsInput.get().split(",");
        String[] taxonValues = new String[labels.size()];
        double[] values = new double[labels.size()];
        for (String t : traits) {
            t = t.replaceAll("\\s+", " ");
            String[] strs = t.split("=");
            if (strs.length != 2) {
                throw new IllegalArgumentException("could not parse trait: " + t);
            }
            String taxonID = normalize(strs[0]);
            int taxonNr = labels.indexOf(taxonID);
            if (taxonNr < 0) {
                throw new IllegalArgumentException("Trait (" + taxonID + ") is not a known taxon. Spelling error perhaps?");
            }
            taxonValues[taxonNr] = normalize(strs[1]);
            try {
                values[taxonNr] = trait.convertValueToDouble(taxonValues[taxonNr]);
            } catch (DateTimeParseException ex) {
                Log.err.println("Failed to parse date '" + taxonValues[taxonNr] + "' using format '" + trait.dateTimeFormatInput.get() + "'.");
                System.exit(1);
            } catch (IllegalArgumentException ex) {
                Log.err.println("Failed to parse date '" + taxonValues[taxonNr] + "'.");
                System.exit(1);
            }

            map.put(taxonID, taxonNr);
        }

        // find extremes
        double minValue = values[0];
        double maxValue = values[0];
        for (double value : values) {
            minValue = Math.min(minValue, value);
            maxValue = Math.max(maxValue, value);
        }
        
        if (trait.traitNameInput.get().equals(trait.DATE_TRAIT) || trait.traitNameInput.get().equals(trait.DATE_FORWARD_TRAIT)) {
        	return maxValue;
        }

        if (trait.traitNameInput.get().equals(trait.DATE_BACKWARD_TRAIT) || trait.traitNameInput.get().equals(trait.AGE_TRAIT)) {
        	return minValue;
        }

        return -1.0;

    	
    }
    
    /**
     * remove start and end spaces
     */
    protected String normalize(String str) {
        if (str.charAt(0) == ' ') {
            str = str.substring(1);
        }
        if (str.endsWith(" ")) {
            str = str.substring(0, str.length() - 1);
        }
        return str;
    }

    


    /**
     * CalculationNode methods *
     */
    @Override
    protected boolean requiresRecalculation() {
        // we only get here if the tree is dirty, which is a StateNode
        // since the StateNode can only become dirty through an operation,
        // we need to recalculate tree intervals
        intervalsKnown = false;
        return true;
    }

    @Override
    protected void restore() {
        //intervalsKnown = false;
        double[] tmp = storedIntervals;
        storedIntervals = intervals;
        intervals = tmp;

        int[] tmp2 = storedLineageCounts;
        storedLineageCounts = lineageCounts;
        lineageCounts = tmp2;

        int tmp3 = storedIntervalCount;
        storedIntervalCount = intervalCount;
        intervalCount = tmp3;
        
        IntervalType[] tmp4 = storedIntervalTypes;
        storedIntervalTypes = intervalTypes;
        intervalTypes = tmp4;

        super.restore();
    }

    @Override
    protected void store() {
        System.arraycopy(lineageCounts, 0, storedLineageCounts, 0, lineageCounts.length);
        System.arraycopy(intervals, 0, storedIntervals, 0, intervals.length);
        System.arraycopy(intervalTypes, 0, storedIntervalTypes, 0, intervalTypes.length);
        storedIntervalCount = intervalCount;
        super.store();
    }

    /**
     * Specifies that the intervals are unknown (i.e., the beast.tree has changed).
     */
    public void setIntervalsUnknown() {
        intervalsKnown = false;
    }


    @Override
	public int getSampleCount() {
        // Assumes a binary tree!
//        return treeInput.get().getInternalNodeCount();
        return -1;
    }

    /**
     * get number of intervals
     */
    @Override
	public int getIntervalCount() {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        return intervalCount;
    }

    /**
     * Gets an interval.
     */
    @Override
	public double getInterval(int i) {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        if (i < 0 || i >= intervalCount) throw new IllegalArgumentException();
        return intervals[i];
    }

    /**
     * Defensive implementation creates copy
     *
     * @return
     */
    public double[] getIntervals(double[] inters) {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        if (inters == null) inters = new double[intervals.length];
        System.arraycopy(intervals, 0, inters, 0, intervals.length);
        return inters;
    }

    public double[] getCoalescentTimes(double[] coalescentTimes) {

        if (!intervalsKnown) {
            calculateIntervals();
        }

        if (coalescentTimes == null) coalescentTimes = new double[getSampleCount()];

        double time = 0;
        int coalescentIndex = 0;
        for (int i = 0; i < intervals.length; i++) {
            time += intervals[i];
            for (int j = 0; j < getCoalescentEvents(i); j++) {
                coalescentTimes[coalescentIndex] = time;
                coalescentIndex += 1;
            }
        }
        return coalescentTimes;
    }

    /**
     * Returns the number of uncoalesced lineages within this interval.
     * Required for s-coalescents, where new lineages are added as
     * earlier samples are come across.
     */
    @Override
	public int getLineageCount(int i) {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        if (i >= intervalCount) throw new IllegalArgumentException();
        return lineageCounts[i];
    }
    
    /**
     * Returns the number of coalescent events in an interval
     */
    @Override
	public int getCoalescentEvents(int i) {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        
        if (i >= intervalCount) throw new IllegalArgumentException();
        
        if (i < intervalCount - 1) {
            return lineageCounts[i] - lineageCounts[i + 1];
        } else {
            return lineageCounts[i] - 1;
        }
        
    }

    /**
     * Returns the type of interval observed.
     */
    @Override
	public IntervalType getIntervalType(int i) {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        return intervalTypes[i];
    }

//    public Node getCoalescentNode(int interval) {
//        if (getIntervalType(interval) == IntervalType.COALESCENT) {
//            if (lineagesRemoved[interval] != null) {
//                if (lineagesRemoved[interval].size() == 1) {
//                    return lineagesRemoved[interval].get(0);
//                } else throw new IllegalArgumentException("multiple lineages lost over this interval!");
//            } else throw new IllegalArgumentException("Inconsistent: no intervals lost over this interval!");
//        } else throw new IllegalArgumentException("Interval " + interval + " is not a coalescent interval.");
//    }

    /**
     * get the total height of the genealogy represented by these
     * intervals.
     */
    @Override
	public double getTotalDuration() {

        if (!intervalsKnown) {
            calculateIntervals();
        }
        double height = 0.0;
        for (int j = 0; j < intervalCount; j++) {
            height += intervals[j];
        }
        return height;
    }

    /**
     * Checks whether this set of coalescent intervals is fully resolved
     * (i.e. whether is has exactly one coalescent event in each
     * subsequent interval)
     */
    @Override
	public boolean isBinaryCoalescent() {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        for (int i = 0; i < intervalCount; i++) {
            if (getCoalescentEvents(i) > 0) {
                if (getCoalescentEvents(i) != 1) return false;
            }
        }

        return true;
    }

    /**
     * Checks whether this set of coalescent intervals coalescent only
     * (i.e. whether is has exactly one or more coalescent event in each
     * subsequent interval)
     */
    @Override
	public boolean isCoalescentOnly() {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        for (int i = 0; i < intervalCount; i++) {
            if (getCoalescentEvents(i) < 1) return false;
        }

        return true;
    }

    /**
     * Recalculates all the intervals for the given beast.tree.
     */
    @SuppressWarnings("unchecked")
    protected void calculateIntervals() {
//        Tree tree = treeInput.get();

        int nodeCount = 0;
        // compute the total number of nodes, add +1 for a migration event
        for (Tree tree : treeInput.get())
        	nodeCount += tree.getNodeCount()+1;

        times = new double[nodeCount];
        int[] childCounts = new int[nodeCount];

        collectTimes(treeInput.get(), offset, rootLengthInput.get(), times, childCounts);

        indices = new int[nodeCount];

        HeapSort.sort(times, indices);        


        if (intervals == null || intervals.length != nodeCount) {
            intervals = new double[nodeCount];
            lineageCounts = new int[nodeCount];
            intervalTypes = new IntervalType[nodeCount];
            storedIntervals = new double[nodeCount];
            storedLineageCounts = new int[nodeCount];
            storedIntervalTypes = new IntervalType[nodeCount];
        } 

        // start is the time of the first tip
        double start = times[indices[0]];
        int numLines = 0;
        int nodeNo = 0;
        intervalCount = 0;
        
        boolean[] isDirty = new boolean[nodeCount];

        
        while (nodeNo < nodeCount) {

            int lineagesRemoved = 0;
            int lineagesAdded = 0;

            double finish = times[indices[nodeNo]];
            double next;

            do {
                final int childIndex = indices[nodeNo];
                final int childCount = childCounts[childIndex];
                // don't use nodeNo from here on in do loop
                nodeNo += 1;
                if (childCount == 0) {
                    lineagesAdded += 1;
                    intervalTypes[nodeNo-1] = IntervalType.SAMPLE;
                } else if (childCount == 1){
                    lineagesRemoved += 1;
                    intervalTypes[nodeNo-1] = IntervalType.MIGRATION;
                } else{
                    lineagesRemoved += (childCount - 1);
                    intervalTypes[nodeNo-1] = IntervalType.COALESCENT;
                }

                if (nodeNo < nodeCount) {
                    next = times[indices[nodeNo]];
                } else break;
            } while (Math.abs(next - finish) <= multifurcationLimit);

            if (lineagesAdded > 0) {

                if (intervalCount > 0 || ((finish - start) > multifurcationLimit)) {
                    intervals[intervalCount] = finish - start;
                    lineageCounts[intervalCount] = numLines;
                    intervalCount += 1;
                }

                start = finish;
            }

            // add sample event
            numLines += lineagesAdded;

            if (lineagesRemoved > 0) {
                intervals[intervalCount] = finish - start;
                lineageCounts[intervalCount] = numLines;
                intervalCount += 1;
                start = finish;
            }
            // coalescent event
            numLines -= lineagesRemoved;
        }
        
        double[] corrtime = new double[times.length];
        for (int i=0; i < times.length;i++) {
        	corrtime[i] = times[indices[i]];
        }
        intervalsKnown = true;
    }

    /**
     * Returns the time of the start of an interval
     *
     * @param i which interval
     * @return start time
     */
    public double getIntervalTime(int i) {
        if (!intervalsKnown) {
            calculateIntervals();
        }
        return times[indices[i]];
    }
    

    /**
     * @return the delta parameter of Pybus et al (Node spread statistic)
     */
    public double getDelta() {

        return IntervalList.Utils.getDelta(this);
    }

    /**
     * extract coalescent times and tip information into array times from beast.tree.
     *
     * @param tree        the beast.tree
     * @param times       the times of the nodes in the beast.tree
     * @param childCounts the number of children of each node
     */
    protected static void collectTimes(List<Tree> trees, double[] offset, List<RealParameter> rootLength, double[] times, int[] childCounts) {
    	int c = 0;
    	int ti = 0;

    	for (Tree tree : trees) {
	        Node[] nodes = tree.getNodesAsArray();
	        for (int i = 0; i < nodes.length; i++) {
	            Node node = nodes[i];
	            times[c] = node.getHeight() + offset[ti];
	            childCounts[c] = node.isLeaf() ? 0 : 2;
	            c++;
	        }
	        times[c] = tree.getRoot().getHeight() + offset[ti] + rootLength.get(ti).getValue();
            childCounts[c] = 1;
	        c++;
	        ti++;
    	}
    }

    /**
     * The beast.tree. RRB: not a good idea to keep a copy around, since it changes all the time.
     */
//    private Tree tree = null;

    /**
     * The widths of the intervals.
     */
    protected double[] intervals;
    protected double[] storedIntervals;

    /** interval times **/
    double[] times;
    int[] indices;
    
    protected IntervalType[] intervalTypes;
    protected IntervalType[] storedIntervalTypes;

    
    /**
     * The number of uncoalesced lineages within a particular interval.
     */
    protected int[] lineageCounts;
    protected int[] storedLineageCounts;

    /**
     * The lineages in each interval (stored by node ref).
     */
    protected int intervalCount = 0;
    protected int storedIntervalCount = 0;

    /**
     * are the intervals known?
     */
    protected boolean intervalsKnown = false;

    protected double multifurcationLimit = -1.0;
}