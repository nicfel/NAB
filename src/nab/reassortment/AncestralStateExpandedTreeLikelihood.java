package nab.reassortment;





import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import beast.core.Description;
import beast.core.Input;
import beast.core.Input.Validate;
import beast.core.parameter.IntegerParameter;
import beast.evolution.alignment.Alignment;
import beast.evolution.datatype.DataType;
import beast.evolution.datatype.UserDataType;
import beast.evolution.likelihood.LeafTrait;
import beast.evolution.likelihood.TreeLikelihood;
import beast.evolution.sitemodel.SiteModel;
import beast.evolution.substitutionmodel.Frequencies;
import beast.evolution.substitutionmodel.SubstitutionModel;
import beast.evolution.tree.Node;
import beast.evolution.tree.Tree;
import beast.evolution.tree.TreeInterface;
import beast.evolution.tree.TreeTrait;
import beast.evolution.tree.TreeTraitProvider;
import beast.util.Randomizer;

/**
 * @author Marc A. Suchard
 * @author Alexei Drummond
 */
@Description("Ancestral State Tree Likelihood, adapted for gamma rates by nfm")
public class AncestralStateExpandedTreeLikelihood extends ExpandedTreeLikelihood implements TreeTraitProvider {
    public static final String STATES_KEY = "states";

    public Input<Boolean> useMAPInput = new Input<Boolean>("useMAP","whether to use maximum aposteriori assignments or sample", false);
    public Input<Boolean> returnMLInput = new Input<Boolean>("returnML", "report integrate likelihood of tip data", true);
    
    public Input<Boolean> useJava = new Input<Boolean>("useJava", "prefer java, even if beagle is available", true);
    
    public Input<Boolean> sampleTipsInput = new Input<Boolean>("sampleTips", "if tips have missing data/ambigous values sample them for logging (default true)", true);
    
	public Input<List<LeafTrait>> leafTriatsInput = new Input<List<LeafTrait>>("leaftrait", "list of leaf traits",
			new ArrayList<LeafTrait>());

	int[][] storedTipStates;

	/** parameters for each of the leafs **/
	IntegerParameter[] parameters;

	/** and node number associated with parameter **/
	int[] leafNr;

	int traitDimension;

    /**
     * Constructor.
     * Now also takes a DataType so that ancestral states are printed using data codes
     *
     * @param patternList     -
     * @param treeModel       -
     * @param siteModel       -
     * @param branchRateModel -
     * @param useAmbiguities  -
     * @param storePartials   -
     * @param dataType        - need to provide the data-type, so that corrent data characters can be returned
     * @param tag             - string label for reconstruction characters in tree log
     * @param forceRescaling  -
     * @param useMAP          - perform maximum aposteriori reconstruction
     * @param returnML        - report integrate likelihood of tip data
     */
    int patternCount;
    int stateCount;
    int siteCount;

    int[][] tipStates; // used to store tip states when using beagle
    
    
    @Override
    public void initAndValidate() {
        
        treeTraits.addTrait(STATES_KEY, new TreeTrait.IA() {
            public String getTraitName() {
                return tag;
            }

            public Intent getIntent() {
                return Intent.NODE;
            }

            public int[] getTrait(TreeInterface tree, Node node) {
                return getStatesForNode(tree,node);
            }

            public String getTraitString(TreeInterface tree, Node node) {
                return formattedState(getStatesForNode(tree,node), dataType);
            }
        });

    }
    
    @Override
    public void initialize() {
    	if (dataInput.get().getSiteCount() == 0) {
    		return;
    	}
    	    	
    	
    	String sJavaOnly = null;
    	if (useJava.get()) {
    		sJavaOnly = System.getProperty("java.only");
    		System.setProperty("java.only", "" + true);
    	}
    	super.initAndValidate();
    	if (useJava.get()) {
	    	if (sJavaOnly != null) {
	    		System.setProperty("java.only", sJavaOnly);
	    	} else {
	    		System.clearProperty("java.only");
	    	}
    	}
    	
    	super.initialize();


        TreeInterface treeModel = tree;
        patternCount = dataInput.get().getPatternCount();
        dataType = dataInput.get().getDataType();
        stateCount = dataType.getStateCount();
        siteCount = dataInput.get().getSiteCount();

        reconstructedStates = new int[treeModel.getNodeCount()][siteCount];
        storedReconstructedStates = new int[treeModel.getNodeCount()][patternCount];

        this.useMAP = useMAPInput.get();
        this.returnMarginalLogLikelihood = returnMLInput.get();

        int tipCount = treeModel.getLeafNodeCount();
        tipStates = new int[tipCount][];

        Alignment data = dataInput.get();
        
    	int gap = data.getDataType().string2state(String.valueOf(data.getDataType().GAP_CHAR)).get(0);


        for (Node node : tree.getExternalNodes()) {
            String taxon = node.getID();
            int taxonIndex = data.getTaxonIndex(taxon);
            if (taxonIndex == -1) {
            	if (taxon.startsWith("'") || taxon.startsWith("\"")) {
                    taxonIndex = data.getTaxonIndex(taxon.substring(1, taxon.length() - 1));
                }
            }
            tipStates[node.getNr()] = new int[patternCount];
            if (!m_useAmbiguities.get()) {
            	likelihoodCore.getNodeStates(node.getNr(), tipStates[node.getNr()]);
            } else {
            	int [] states = tipStates[node.getNr()];
	            for (int i = 0; i < patternCount; i++) {
	            	if (taxonIndex!=-1) {
		                int code = data.getPattern(taxonIndex, i);
		                int[] statesForCode = data.getDataType().getStatesForCode(code);
		                if (statesForCode.length==1) {
		                    states[i] = statesForCode[0];
		                }else {
		                    states[i] = code; // Causes ambiguous states to be ignored.
		                }
	            	}else {
	            		states[i] = gap;
	            	}
	            }

            }
    	}
        
//        if (m_siteModel.getCategoryCount() > 1)
//            throw new RuntimeException("Reconstruction not implemented for multiple categories yet.");
        
        
        // stuff for dealing with ambiguities in tips
        if (!m_useAmbiguities.get() && leafTriatsInput.get().size() == 0) {
        	return;
        }
		traitDimension = tipStates[0].length;
 
		leafNr = new int[leafTriatsInput.get().size()];
		parameters = new IntegerParameter[leafTriatsInput.get().size()];

		List<String> taxaNames = dataInput.get().getTaxaNames();
		for (int i = 0; i < leafNr.length; i++) {
			LeafTrait leafTrait = leafTriatsInput.get().get(i);
			parameters[i] = leafTrait.parameter.get();
			// sanity check
			if (parameters[i].getDimension() != traitDimension) {
				throw new IllegalArgumentException("Expected parameter dimension to be " + traitDimension + ", not "
						+ parameters[i].getDimension());
			}
			// identify node
			String taxon = leafTrait.taxonName.get();
			int k = 0;
			while (k < taxaNames.size() && !taxaNames.get(k).equals(taxon)) {
				k++;
			}
			leafNr[i] = k;
			// sanity check
			if (k == taxaNames.size()) {
				throw new IllegalArgumentException("Could not find taxon '" + taxon + "' in tree");
			}
			// initialise parameter value from states
			Integer[] values = new Integer[tipStates[k].length];
			for (int j = 0; j < tipStates[k].length; j++) {
				values[j] = tipStates[k][j];
			}
			IntegerParameter p = new IntegerParameter(values);
			p.setLower(0);
			p.setUpper(dataType.getStateCount()-1);
			parameters[i].assignFromWithoutID(p);
		}

		storedTipStates = new int[tipStates.length][traitDimension];
		for (int i = 0; i < tipStates.length; i++) {
			System.arraycopy(tipStates[i], 0, storedTipStates[i], 0, traitDimension);
		}


    }

    @Override
    public void store() {
        super.store();

        for (int i = 0; i < reconstructedStates.length; i++) {
            System.arraycopy(reconstructedStates[i], 0, storedReconstructedStates[i], 0, reconstructedStates[i].length);
        }

        storedAreStatesRedrawn = areStatesRedrawn;
        storedJointLogLikelihood = jointLogLikelihood;
        
        
        // deal with ambiguous tips
        if (leafNr != null) {
			for (int i = 0; i < leafNr.length; i++) {
				int k = leafNr[i];
				System.arraycopy(tipStates[k], 0, storedTipStates[k], 0, traitDimension);
			}
        }
    }

    @Override
    public void restore() {

        super.restore();

        int[][] temp = reconstructedStates;
        reconstructedStates = storedReconstructedStates;
        storedReconstructedStates = temp;

        areStatesRedrawn = storedAreStatesRedrawn;
        jointLogLikelihood = storedJointLogLikelihood;
        
        // deal with ambiguous tips
        if (leafNr != null) {
			for (int i = 0; i < leafNr.length; i++) {
				int k = leafNr[i];
				int[] tmp = tipStates[k];
				tipStates[k] = storedTipStates[k];
				storedTipStates[k] = tmp;
				// Does not handle ambiguities or missing taxa
				likelihoodCore.setNodeStates(k, tipStates[k]);
			}
        }
    }
    
    @Override
    protected boolean requiresRecalculation() {
    	likelihoodKnown = false;

    	boolean isDirty = super.requiresRecalculation();
    	if (!m_useAmbiguities.get()) {
    		return isDirty;
    	}
    	
    	
    	int hasDirt = Tree.IS_CLEAN;
		
		// check whether any of the leaf trait parameters changed
		for (int i = 0; i < leafNr.length; i++) {
			if (parameters[i].somethingIsDirty()) {
				int k = leafNr[i];
				for (int j = 0; j < traitDimension; j++) {
					tipStates[k][j] = parameters[i].getValue(j);
				}
				likelihoodCore.setNodeStates(k, tipStates[k]);
				isDirty = true;
				// mark leaf's parent node as dirty
				Node leaf = tree.getNode(k);
				// leaf.makeDirty(Tree.IS_DIRTY);
				leaf.getParent().makeDirty(Tree.IS_DIRTY);
	            hasDirt = Tree.IS_DIRTY;
			}
		}
		isDirty |= super.requiresRecalculation();
		this.hasDirt |= hasDirt;

		return isDirty;
    	
    	
    }
//    protected void handleModelChangedEvent(Model model, Object object, int index) {
//        super.handleModelChangedEvent(model, object, index);
//        fireModelChanged(model);
//    }
    
    

    public DataType getDataType() {
        return dataType;
    }

    public int[] getStatesForNode(TreeInterface tree, Node node) {
        if (tree != tree) {
            throw new RuntimeException("Can only reconstruct states on treeModel given to constructor");
        }

        if (!likelihoodKnown) {
        	try {
        		 calculateLogP();
        	} catch (Exception e) {
				throw new RuntimeException(e.getMessage());
			}
        }

        if (!areStatesRedrawn) {
            redrawAncestralStates();
        }
        return reconstructedStates[node.getNr()];
    }


    public void redrawAncestralStates() {
        jointLogLikelihood = 0;
        TreeInterface tree = this.tree;
        traverseSample(tree, tree.getRoot(), null, null);
        
        areStatesRedrawn = true;
    }

//    private boolean checkConditioning = true;

    
    @Override
    public double calculateLogP() {
    	initialize();
        areStatesRedrawn = false;
        double marginalLogLikelihood = super.calculateLogP();
        likelihoodKnown = true;

        if (returnMarginalLogLikelihood) {
            return logP;
        }
        // redraw states and return joint density of drawn states
        redrawAncestralStates();
        logP = jointLogLikelihood;
        return logP;
    }

    protected TreeTraitProvider.Helper treeTraits = new Helper();

    public TreeTrait[] getTreeTraits() {
        return treeTraits.getTreeTraits();
    }

    public TreeTrait getTreeTrait(String key) {
        return treeTraits.getTreeTrait(key);
    }


    private static String formattedState(int[] state, DataType dataType) {
        StringBuffer sb = new StringBuffer();
        sb.append("\"");
        if (dataType instanceof UserDataType) {
            boolean first = true;
            for (int i : state) {
                if (!first) {
                    sb.append(" ");
                } else {
                    first = false;
                }

                sb.append(dataType.getCode(i));
            }

        } else {
            for (int i : state) {
                sb.append(dataType.getChar(i));
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private int drawChoice(double[] measure) {
        if (useMAP) {
            double max = measure[0];
            int choice = 0;
            for (int i = 1; i < measure.length; i++) {
                if (measure[i] > max) {
                    max = measure[i];
                    choice = i;
                }
            }
            return choice;
        } else {
            return Randomizer.randomChoicePDF(measure);
        }
    }

    public void getStates(int tipNum, int[] states)  {
        // Saved locally to reduce BEAGLE library access
        System.arraycopy(tipStates[tipNum], 0, states, 0, states.length);
    }

//	public void getPartials(int number, double[] partials) {
//        int cumulativeBufferIndex = Beagle.NONE;
//        /* No need to rescale partials */
//        beagle.beagle.getPartials(beagle.partialBufferHelper.getOffsetIndex(number), cumulativeBufferIndex, partials);
//	}
//
//	public void getTransitionMatrix(int matrixNum, double[] probabilities) {
//		beagle.beagle.getTransitionMatrix(beagle.matrixBufferHelper.getOffsetIndex(matrixNum), probabilities);
//	}
    
    /**
     * Traverse (pre-order) the tree sampling the internal node states.
     *
     * @param tree        - TreeModel on which to perform sampling
     * @param node        - current node
     * @param parentState - character state of the parent node to 'node'
     */
    public void traverseSample(TreeInterface tree, Node node, int[] parentState, int[] category) {

        int nodeNum = node.getNr();

        Node parent = node.getParent();

        // This function assumes that all partial likelihoods have already been calculated
        // If the node is internal, then sample its state given the state of its parent (pre-order traversal).

        double[] conditionalProbabilities = new double[stateCount];
        int[] state = new int[siteCount];
        // keeps track of which category an
        if (category==null)
        	category = new int[siteCount];

        if (!node.isLeaf()) {

            if (parent == null) {
            	
                double[] partialLikelihood = new double[stateCount * patternCount * m_siteModel.getCategoryCount()];
                likelihoodCore.getNodePartials(node.getNr(), partialLikelihood);
                
                final double[] proportions = m_siteModel.getCategoryProportions(node);

                double[] rootProbabilities = new double[stateCount * m_siteModel.getCategoryCount()];

                double[] rootFrequencies = substitutionModel.getFrequencies();
//                if (rootFrequenciesInput.get() != null) {
//                    rootFrequencies = rootFrequenciesInput.get().getFreqs();
//                }

                // This is the root node
                for (int j = 0; j < siteCount; j++) {
//                	if (beagle != null) {
//                		getPartials(node.getNr(), conditionalProbabilities);
//                	} else {
                	//TODO switch to sites
                	
                	
                	for (int a = 0; a < m_siteModel.getCategoryCount(); a++) {
                		for (int b = 0; b < stateCount; b++) {
                			rootProbabilities[a*stateCount+b] = rootFrequencies[b]*proportions[a] * partialLikelihood[a*(stateCount * patternCount) + dataInput.get().getPatternIndex(j) * stateCount+b];
                		}
                	}                    
                    
                    try {
                         int val = drawChoice(rootProbabilities);
                         category[j] = val/m_siteModel.getCategoryCount();
                         state[j] = val % m_siteModel.getCategoryCount();
                    } catch (Error e) {
                        System.err.println(e.toString());
                        state[j] = 0;
                    }

                    
                    
                    reconstructedStates[nodeNum][j] = state[j];
                }

            } else {
            	
            	

                // This is an internal node, but not the root
                double[] partialLikelihood = new double[stateCount * patternCount * m_siteModel.getCategoryCount()];

                double[][] matrix = new double[m_siteModel.getCategoryCount()][probabilities.length];
                likelihoodCore.getNodePartials(node.getNr(), partialLikelihood);
            	for (int a = 0; a < m_siteModel.getCategoryCount(); a++) {
                    likelihoodCore.getNodeMatrix(nodeNum, a, matrix[a]);
            	}
            	
            	

                for (int j = 0; j < siteCount; j++) {

                    int parentIndex = parentState[j] * stateCount;
                    int childIndex = j * stateCount;

                    for (int i = 0; i < stateCount; i++) {
                        conditionalProbabilities[i] = partialLikelihood[category[j]*(stateCount * patternCount) + dataInput.get().getPatternIndex(j) * stateCount+i] * 
                        		matrix[category[j]][parentIndex + i];
                    }

                    state[j] = drawChoice(conditionalProbabilities);
                    reconstructedStates[nodeNum][j] = state[j];
                    double contrib = probabilities[parentIndex + state[j]];
                }
            }

            // Traverse down the two child nodes
            Node child1 = node.getChild(0);
            traverseSample(tree, child1, state, category);

            Node child2 = node.getChild(1);
            traverseSample(tree, child2, state, category);
        } else {

            // This is an external leaf
        	int[] currState = new int[patternCount];
        	getStates(nodeNum, currState);
            for (int j = 0; j < siteCount; j++) {
            	reconstructedStates[nodeNum][j] = currState[dataInput.get().getPatternIndex(j)];
            }
        	double[][] matrix = new double[m_siteModel.getCategoryCount()][probabilities.length];
        	for (int a = 0; a < m_siteModel.getCategoryCount(); a++) {
                likelihoodCore.getNodeMatrix(nodeNum, a, matrix[a]);
        	}
        	
        	if (sampleTipsInput.get()) {
	            // Check for ambiguity codes and sample them
        		int nrmuts = 0;
	            for (int j = 0; j < siteCount; j++) {
	
	                final int thisState = reconstructedStates[nodeNum][j];
	                final int parentIndex = parentState[j] * stateCount;
	                
	                if (dataType.isAmbiguousCode(thisState)) {		                    
	                    boolean [] stateSet = dataType.getStateSet(thisState);
	                    for (int i = 0; i < stateCount; i++) {
	                        conditionalProbabilities[i] =  stateSet[i] ? matrix[category[j]][parentIndex + i] : 0;
	                    }
//	                    if (node.getID().contentEquals("reaExtinct_64")) {
//	                    	System.out.println(Arrays.toString(conditionalProbabilities));
//	                    }
	                    	
	                    reconstructedStates[nodeNum][j] = drawChoice(conditionalProbabilities);
	                    if (reconstructedStates[nodeNum][j]!=parentState[j])
	                    	nrmuts++;
	                }
	                
	                double length = parent.getHeight()-node.getHeight();
	                

	
	                double contrib = probabilities[parentIndex + reconstructedStates[nodeNum][j]];
	                //System.out.println("Pr(" + parentState[j] + ", " + reconstructedStates[nodeNum][j] +  ") = " + contrib);
	                jointLogLikelihood += Math.log(contrib);
	            }
	            
//	            if (node.getID().contentEquals("reaExtinct_64")) {
//	            	for (int a = 0; a < m_siteModel.getCategoryCount(); a++) {
//	                    System.out.println(Arrays.toString(matrix[a]));
//	            	}
//	            	System.out.println(Arrays.toString(category));
//	            	System.out.println(nodeNum+ " " +nrmuts + " " + (parent.getHeight()-node.getHeight()));
////	            	System.out.println(tree);
////	            	System.exit(0);
//	            }

        	}
        	
        }
    }
    
    
    @Override
    public void log(final long sample, final PrintStream out) {
    	// useful when logging on a fixed tree in an AncestralTreeLikelihood that is logged, but not part of the posterior
    	hasDirt = Tree.IS_FILTHY;
    	calculateLogP();
        out.print(getCurrentLogP() + "\t");
    }


    protected DataType dataType;
    private int[][] reconstructedStates;
    private int[][] storedReconstructedStates;

    private String tag;
    private boolean areStatesRedrawn = false;
    private boolean storedAreStatesRedrawn = false;

    private boolean useMAP = false;
    private boolean returnMarginalLogLikelihood = true;

    private double jointLogLikelihood;
    private double storedJointLogLikelihood;

    boolean likelihoodKnown = false;
}
