package nab.multitree;

import java.io.PrintStream;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

//import org.jblas.DoubleMatrix;

import beast.core.CalculationNode;
import beast.core.Citation;
import beast.core.Description;
import beast.core.Function;
import beast.core.Input;
import beast.core.StateNode;
import beast.core.Input.Validate;
import beast.core.Loggable;
import beast.core.parameter.BooleanParameter;
import beast.core.util.Log;
import beast.evolution.branchratemodel.BranchRateModel;
import beast.evolution.tree.Node;
import beast.evolution.tree.Tree;
import beast.evolution.tree.TreeInterface;
import beast.evolution.tree.coalescent.IntervalType;
import beast.mascot.dynamics.Dynamics;
import beast.mascot.ode.*;
import beast.util.Randomizer;
import cern.colt.Arrays;

/**
 * @author Nicola Felix Mueller
 */

@Description("Calculates the probability of a beast.tree using under the framework of Mueller (2017).")
@Citation("Nicola F. Müller, David A. Rasmussen, Tanja Stadler (2017)\n  The Structured Coalescent and its Approximations.\n  Mol Biol Evol 2017 msx186. doi: 10.1093/molbev/msx186")
public class MappedMultitreeMascot extends MultitreeMascot implements Loggable {

	public Input<Double> maxIntegrationStepMappingInput = new Input<>("maxIntegrationStepMapping",
			"maxIntegrationStepMappingas percentage of tree height", 0.001);

	public Input<BranchRateModel.Base> clockModelInput = new Input<BranchRateModel.Base>("branchratemodel",
			"rate to be logged with branches of the tree");
	public Input<List<Function>> parameterInput = new Input<List<Function>>("metadata",
			"meta data to be logged with the tree nodes", new ArrayList<>());
	public Input<Boolean> maxStateInput = new Input<Boolean>("maxState",
			"report branch lengths as substitutions (branch length times clock rate for the branch)", false);
	public Input<BooleanParameter> conditionalStateProbsInput = new Input<BooleanParameter>("conditionalStateProbs",
			"report branch lengths as substitutions (branch length times clock rate for the branch)");
	public Input<Boolean> substitutionsInput = new Input<Boolean>("substitutions",
			"report branch lengths as substitutions (branch length times clock rate for the branch)", false);
	public Input<Integer> decimalPlacesInput = new Input<Integer>("dp",
			"the number of decimal places to use writing branch lengths and rates, use -1 for full precision (default = full precision)",
			-1);
	public Input<Boolean> internalNodesOnlyInput = new Input<Boolean>("internalNodesOnly",
			"If true, migration events are not logged default false",
			false);
    final public Input<Integer> minClusterSizeInput = new Input<>("minClusterSize", 
    		"A population size model", 0);


	Map<Integer, List<double[]>> intermediateStateProbs;
	Map<Integer, List<Double>> intermediateTimes;

	protected DecimalFormat df;
	protected boolean someMetaDataNeedsLogging;
	protected boolean substitutions = false;

	public List<Tree> mappedTrees;

	List<Integer> activeStates;
	double[] migrationRates;
	
	long lastLog=-1;

	@Override
	public void initAndValidate() {
    	super.initAndValidate();
    	

		if (parameterInput.get().size() == 0 && clockModelInput.get() == null) {
			someMetaDataNeedsLogging = false;
			return;
			// throw new Exception("At least one of the metadata and branchratemodel inputs
			// must be defined");
		}
		someMetaDataNeedsLogging = true;
		// without substitution model, reporting substitutions == reporting branch
		// lengths
		if (clockModelInput.get() != null) {
			substitutions = substitutionsInput.get();
		}

		int dp = decimalPlacesInput.get();

		if (dp < 0) {
			df = null;
		} else {
			// just new DecimalFormat("#.######") (with dp time '#' after the decimal)
			df = new DecimalFormat("#." + new String(new char[dp]).replace('\0', '#'));
			df.setRoundingMode(RoundingMode.HALF_UP);
		}
	}

	public void calcForLogging(long sample) {
		if (lastLog!=sample) {
			calculateLogP();
			lastLog=sample;
		}
	}

	@Override
	public double calculateLogP() {
    	// newly calculate tree intervals (already done by swap() below)
    	treeIntervals.calculateIntervals();
    	
    	
    	mappedTrees = new ArrayList<>();
		for (Tree t : treeIntervals.treeInput.get()){
			mappedTrees.add(new Tree(t.getRoot().copy()));
			mappedTrees.get(mappedTrees.size()-1).getRoot().sort();
		}

		intermediateStateProbs = new HashMap<>();
		intermediateTimes = new HashMap<>();

		double maxStepSize = treeIntervals.rootHeight * maxIntegrationStepMappingInput.get();


        // Set up ArrayLists for the indices of active lineages and the lineage state probabilities
        activeLineages.clear();
        logP = 0;
        nrLineages = 0;
        //linProbs = new double[0];// initialize the tree and rates interval counter
        linProbsLength = 0;
        int treeInterval = 0, ratesInterval = 0;        
     	double nextEventTime = 0.0;
        
        // Time to the next rate shift or event on the tree
        double nextTreeEvent = treeIntervals.getInterval(treeInterval);
        double nextRateShift = dynamics.getInterval(ratesInterval);
        
//        if (first == 0 || !dynamics.areDynamicsKnown()) {
        	setUpDynamics();
//        }

		coalescentRates = dynamics.getCoalescentRate(ratesInterval);  
        migrationRates = dynamics.getBackwardsMigration(ratesInterval);
		//indicators = dynamics.getIndicators(ratesInterval);
		nrLineages = activeLineages.size();
		linProbsLength = nrLineages * states;
		double currTime = 0.0;
		double lastRateShift = currTime;


        // Calculate the likelihood
        do {       
			nextEventTime = Math.min(nextTreeEvent, nextRateShift);
			if (nextEventTime > 0) { // if true, calculate the interval contribution
				if (recalculateLogP) {
					System.err.println("ode calculation stuck, reducing tolerance, new tolerance= " + maxTolerance);
					maxTolerance *= 0.9;
					recalculateLogP = false;
					System.exit(0);
					return calculateLogP();
				}
				
				if (nextEventTime < maxStepSize) {
					if (nrLineages>0) {
						logP += doEuler(nextEventTime, ratesInterval);
					}
					currTime += nextEventTime;
					storeIntermediateResults(currTime);
					
				} else {
					int nrIntermediates = (int) (nextEventTime / maxStepSize);
					double oldCurrTime = currTime;
					for (int i = 0; i < (nrIntermediates + 1); i++) {
						if (nrLineages>0) {
							logP += doEuler(nextEventTime / (nrIntermediates + 1), ratesInterval);
						}
						currTime += nextEventTime / (nrIntermediates + 1);
						if (i == nrIntermediates)
							currTime = oldCurrTime + nextEventTime;

						storeIntermediateResults(currTime);
					}
				}
			}
       	
        	if (nextTreeEvent <= nextRateShift){
 	        	if (treeIntervals.getIntervalType(treeInterval) == IntervalType.COALESCENT) {
// 	        		System.out.println("c");
// 	        		System.out.print(String.format("%.3f ", nextTreeEvent));
//	        		logP += normalizeLineages(linProbs);									// normalize all lineages before event		
 	        		nrLineages--;													// coalescent event reduces the number of lineages by one
	        		logP += coalesce(treeInterval, ratesInterval, nextTreeEvent, nextRateShift, currTime);	  				// calculate the likelihood of the coalescent event
	        		if (Double.isNaN(logP)) {
	        			logP = Double.NEGATIVE_INFINITY;
	        		}
	        	}
 	       		
 	       		if (treeIntervals.getIntervalType(treeInterval) == IntervalType.SAMPLE) {
// 	       			System.out.println("s");
 	       			//if (linProbsLength > 0)
 	       			//	logP += normalizeLineages(linProbs);								// normalize all lineages before event
	       			nrLineages++;													// sampling event increases the number of lineages by one
 	       			sample(treeInterval, ratesInterval, nextTreeEvent, nextRateShift, currTime);							// calculate the likelihood of the sampling event if sampling rate is given
	       		}	
 	       		
 	       		if (treeIntervals.getIntervalType(treeInterval) == IntervalType.MIGRATION) { 
// 	       			System.out.println("m");
 	        		nrLineages--;													// coalescent event reduces the number of lineages by one
 	        		introduction(treeInterval, currTime);	 	
                	double mig = Math.exp(immigrationRate.getRate(currTime));
                	logP += Math.log(mig);
	       		}	
 	       		

 	       		
 	       		treeInterval++;
        		nextRateShift -= nextTreeEvent;   
        		try{
        			nextTreeEvent = treeIntervals.getInterval(treeInterval);
        		}catch(Exception e){
        			break;
        		}
        	} else {
        		ratesInterval++;
        		coalescentRates = dynamics.getCoalescentRate(ratesInterval);  
                migrationRates = dynamics.getBackwardsMigration(ratesInterval);
        		//indicators = dynamics.getIndicators(ratesInterval);  
        		nextTreeEvent -= nextRateShift;
 	       		nextRateShift = dynamics.getInterval(ratesInterval);
				lastRateShift = currTime;

        	}
//       		System.out.println(logP);

        	if (logP == Double.NEGATIVE_INFINITY) {
        		return logP;
        	}
            if (debug) {
            	Log.info(treeInterval + " " + ratesInterval + " " + logP);
            }        	
        } while(nextTreeEvent <= Double.POSITIVE_INFINITY);

        first++;
		resample(treeInterval, ratesInterval, lastRateShift);

//		System.out.println("");
		return logP;
	}
	
	@Override
	protected void setUpDynamics() {
    	int n = dynamics.getEpochCount();
    	double [][] coalescentRates = new double[n][];
    	double [][] migrationRates = new double[n][];
    	int [][] indicators = new int[n][];
    	double [] nextRateShift = dynamics.getIntervals();
    	for (int i = 0; i < n; i++) {
    		coalescentRates[i] = dynamics.getCoalescentRate(i);  
            migrationRates[i] = dynamics.getBackwardsMigration(i);
    		indicators[i] = dynamics.getIndicators(i);
    	}
//    	dynamics.setDynamicsKnown();
		euler.setUpDynamics(coalescentRates, migrationRates, indicators, nextRateShift);
	}


	protected double coalesce(int currTreeInterval, int currRatesInterval, double nextTreeEvent, double nextRateShift,
			double currTime) {
		double logP = super.coalesce(currTreeInterval, currRatesInterval, nextTreeEvent, nextRateShift);
//		int coalLines0 = treeIntervals.getLineagesRemoved(currTreeInterval, 0);
//		int lineageToAdd = tree.getNode(coalLines0).getParent().getNr();
        int lineageToAdd = treeIntervals.getLineagesAdded(currTreeInterval);

		
		addNewLineage(lineageToAdd, currTime);
		return logP;
	}

	protected void sample(int treeInterval, int ratesInterval, double nextTreeEvent, double nextRateShift,
			double currTime) {
		super.sample(treeInterval, ratesInterval, nextTreeEvent, nextRateShift);
		int sampLin = treeIntervals.getLineagesAdded(treeInterval);
		addNewLineage(sampLin, currTime);
	}
	
//	protected double introduction(int currTreeInterval, int currRatesInterval, double nextTreeEvent, double nextRateShift,
//			double currTime) {
//		double logP = super.introduction(currTreeInterval, currRatesInterval, nextTreeEvent, nextRateShift);
////		int coalLines0 = treeIntervals.getLineagesRemoved(currTreeInterval, 0);
////		int lineageToAdd = tree.getNode(coalLines0).getParent().getNr();
//        int lineageToAdd = treeIntervals.getLineagesAdded(currTreeInterval);
//
//		
//		addNewLineage(lineageToAdd, currTime);
//		return logP;
//	}


	private void addNewLineage(int nr, double time) {
		intermediateStateProbs.put(nr, new ArrayList<>());
		intermediateTimes.put(nr, new ArrayList<>());

		final int daughterIndex1 = activeLineages.indexOf(nr);// .getNr());

		double[] probs = new double[states];

		for (int i = 0; i < states; i++)
			probs[i] = linProbs[daughterIndex1 * states + i];
		
		intermediateStateProbs.get(nr).add(probs);
		intermediateTimes.get(nr).add(time);
	}
	
//	int iii=0;

	private void resample(int treeInterval, int ratesInterval, double lastRateShift) {
		treeInterval--;
		// start by resampling the root
		
		activeStates = new ArrayList<>();
		activeLineages.clear();

		
//    	int coalLines0 = treeIntervals.getLineagesRemoved(treeInterval, 0);	 	   		
//    	activeLineages.add(coalLines0);		
    	// sample the root state
				
		nrLineages = 1;
		linProbsLength = 0;
		
		double currTime = treeIntervals.rootHeight;
		
//		System.out.println(currTime);

//        int[] treenr = treeIntervals.getTree(coalLines0);
        
//		int originState = Randomizer.randomChoicePDF(intermediateStateProbs.get(coalLines0).get(0));
//		System.out.println(mappedTrees.get(treenr[0]));
//		Node root = mappedTrees.get(treenr[0]).getNode(treenr[1]);
//		Node origin = new Node();
//		origin.setHeight(currTime);
//		origin.setMetaData("location", originState);
//
//		origin.addChild(root);
//		root.setParent(origin);    	
		introduceDown(treeInterval, currTime);
    	
//    	System.out.println(treeIntervals.getIntervalType(treeInterval));
//    	System.out.println(treeIntervals.getIntervalType(treeInterval-1));
//    	System.exit(0);


		double nextTreeEvent = treeIntervals.getInterval(treeInterval);
		double nextRateShift = currTime - lastRateShift;

		double nextEventTime;
		// Calculate the likelihood
		do {
			nextEventTime = Math.min(nextTreeEvent, nextRateShift);
			if (nextEventTime > 0) { // if true, calculate the interval contribution
//				System.out.println("currentTime " + currTime);
				sampleMigrationEvents(currTime, currTime - nextEventTime);
				currTime -= nextEventTime;
			}

			if (nextTreeEvent <= nextRateShift) {
				if (treeIntervals.getIntervalType(treeInterval - 1) == IntervalType.COALESCENT) {
//					System.out.println("c");
					nrLineages++; // coalescent event reduces the number of lineages by one
					coalesceDown(treeInterval - 1, currTime); // calculate the
				}

				if (treeIntervals.getIntervalType(treeInterval - 1) == IntervalType.SAMPLE) {
//					System.out.println("s");

					// if (linProbsLength > 0)
					// logP += normalizeLineages(linProbs); // normalize all lineages before event
					nrLineages--; // sampling event increases the number of lineages by one
					sampleDown(treeInterval - 1); // calculate the likelihood of
				}
				
				if (treeIntervals.getIntervalType(treeInterval - 1) == IntervalType.MIGRATION) {
//					System.out.println("i");

					// if (linProbsLength > 0)
					// logP += normalizeLineages(linProbs); // normalize all lineages before event
					nrLineages++; // sampling event increases the number of lineages by one
					introduceDown(treeInterval - 1, currTime); // calculate the likelihood of
				}


				treeInterval--;
				nextRateShift -= nextTreeEvent;
				try {
					nextTreeEvent = treeIntervals.getInterval(treeInterval);
				} catch (Exception e) {
					break;
				}
			} else {
				nextTreeEvent -= nextRateShift;
				ratesInterval--;
				if (ratesInterval == 0) {
					nextRateShift = Double.POSITIVE_INFINITY;
				}else {
					nextRateShift = dynamics.getInterval(ratesInterval);
					coalescentRates = dynamics.getCoalescentRate(ratesInterval - 1);
					migrationRates = dynamics.getBackwardsMigration(ratesInterval - 1);
				}
				// indicators = dynamics.getIndicators(ratesInterval);
			}
		} while (treeInterval > 0);

		first++;

	}


	private void sampleMigrationEvents(double startTime, double endTime) {
		for (int i = 0; i < activeLineages.size(); i++) {
			sampleMigrationEventsLineage(activeLineages.get(i), i, startTime, endTime);
		}
	}

	private void sampleMigrationEventsLineage(Integer nodeNr, int index, double startTime, double endTime) {
		double K = -Math.log(Randomizer.nextDouble());
		double I = 0.0;
		double currentTime = startTime;
				
		int currTimeInterval = intermediateTimes.get(nodeNr).indexOf(startTime);
		if (currTimeInterval == -1) {
			boolean cont = false;
			for (int i = 0; i < intermediateTimes.get(nodeNr).size(); i++)
				if (Math.abs(intermediateTimes.get(nodeNr).get(i) - startTime) < 1e-10) {
					currTimeInterval = i;
					cont = true;
					break;
				}

			if (!cont) {
				for (int i = 0; i < intermediateTimes.get(nodeNr).size(); i++)
					System.err.println(intermediateTimes.get(nodeNr).get(i) - startTime);
				
//				System.out.println(mappedTree);
				

				throw new IllegalArgumentException("timing not found");
			}
		}

		double[] prob_start = intermediateStateProbs.get(nodeNr).get(currTimeInterval);
		

		while (currentTime > (endTime+1e-10)) {
			double[] prob_end = intermediateStateProbs.get(nodeNr).get(currTimeInterval - 1);

			int currState = activeStates.get(index);
			
			double[] integral_state = new double[states];
			
			double dt = currentTime - intermediateTimes.get(nodeNr).get(currTimeInterval - 1);
						
			double sumInt = 0;
			for (int i = 0; i < states; i++) {
				if (i != currState) {
					double rates_start = migrationRates[i * states + currState] * 
							prob_start[i] / prob_start[currState];
					double rates_end = migrationRates[i * states + currState] * 
							prob_end[i] / prob_end[currState];
					
					
					if (prob_start[currState] <= 0 || prob_end[currState] <= 0) {
						integral_state[i] = Double.POSITIVE_INFINITY;
					}else if (prob_end[i] <= 0 || prob_start[i] <= 0){
						integral_state[i] = 0;
					}else{
						integral_state[i] = 0.5 * (rates_end + rates_start) * dt;
					}

					sumInt += integral_state[i];
				}
			}

			if ((I + sumInt) > K) {
				// approximate the height of the new event
				double intermediatePoint = Math.max(0.01, (K - I) / sumInt);
				
				currentTime = currentTime
						- dt * (intermediatePoint);
				
				// update stateprobs for this point
				for (int i = 0; i < states; i++)
					prob_start[i] = (intermediatePoint) * prob_start[i] + (1-intermediatePoint) * prob_end[i];
				
				int newState = -1;
				boolean hasInf = false;
				for (int i = 0; i < integral_state.length; i ++) {
					if (integral_state[i] == Double.POSITIVE_INFINITY && prob_end[i] > 0) {
						newState = i;
						hasInf = true;
					}
				}
				
				// sample migration event
				if (!hasInf)
					newState = Randomizer.randomChoicePDF(integral_state);
				
				if (newState == currState)
					System.exit(0);
				
				int[] treenr = treeIntervals.getTree(nodeNr);

			
				// ad migration event
				Node n = mappedTrees.get(treenr[0]).getNode(treenr[1]);								
				Node p = n.getParent();
				
				Node migNode = new Node();
				migNode.setMetaData("location", currState);
				migNode.setHeight(currentTime);
				migNode.setHeight(currentTime - treeIntervals.offset[treenr[0]]);
				migNode.setNr(Integer.MAX_VALUE);
								
				migNode.setParent(p);
				migNode.addChild(n);


				p.removeChild(n);
				p.addChild(migNode);
				

				n.setParent(migNode);
				
				activeStates.set(index, newState);

				// reset "timers"
				K = -Math.log(Randomizer.nextDouble());
				I = 0.0;
			} else {
				I += sumInt;
				currTimeInterval--;
				if (currTimeInterval == 0)
					break;

				prob_start = intermediateStateProbs.get(nodeNr).get(currTimeInterval);
				currentTime = intermediateTimes.get(nodeNr).get(currTimeInterval);
			}
		}
	}

	private void coalesceDown(int currTreeInterval , double time) {
		int coalLines0 = treeIntervals.getLineagesRemoved(currTreeInterval, 0);
		int coalLines1 = treeIntervals.getLineagesRemoved(currTreeInterval, 1);

//		int lineageToAdd = tree.getNode(coalLines0).getParent().getNr();
		
        int lineageToAdd = treeIntervals.getLineagesAdded(currTreeInterval);
//        activeLineages.add(lineageToAdd);        

        int[] treenr = treeIntervals.getTree(lineageToAdd);
        

		int currState = activeStates.get(activeLineages.indexOf(lineageToAdd));
		mappedTrees.get(treenr[0]).getNode(treenr[1]).setMetaData("location", currState);
		activeStates.remove(activeLineages.indexOf(lineageToAdd));
		activeLineages.remove(activeLineages.indexOf(lineageToAdd));


		activeLineages.add(coalLines0);
		activeLineages.add(coalLines1);
		activeStates.add(currState);
		activeStates.add(currState);

	}


	private void sampleDown(int currTreeInterval) {
		
//		iii++;
		
		int incomingLines = treeIntervals.getLineagesAdded(currTreeInterval);
		
        int[] treenr = treeIntervals.getTree(incomingLines);

		
		mappedTrees.get(treenr[0]).getNode(treenr[1]).setMetaData("location", activeStates.get(activeLineages.indexOf(incomingLines)));
		mappedTrees.get(treenr[0]).getNode(treenr[1]).getMetaData("location");

		activeStates.remove(activeLineages.indexOf(incomingLines));
		activeLineages.remove(activeLineages.indexOf(incomingLines));
	}
	

	private void introduceDown(int currTreeInterval, double time) {
    	int coalLines0 = treeIntervals.getLineagesRemoved(currTreeInterval, 0);	 	   		
    	activeLineages.add(coalLines0);		
    	// sample the root state
    	
        int[] treenr = treeIntervals.getTree(coalLines0);
        
                
		int originState = Randomizer.randomChoicePDF(intermediateStateProbs.get(coalLines0).get(0));
		Node root = mappedTrees.get(treenr[0]).getNode(treenr[1]);
		Node origin = new Node();
		origin.setHeight(time - treeIntervals.offset[treenr[0]]);
//		origin.setMetaData("location", originState);
		origin.setNr(root.getNr());

		origin.addChild(root);
		root.setParent(origin);  
		
		mappedTrees.get(treenr[0]).setRoot(origin);
		// sample rootState
    	activeStates.add(originState);

	}


	private void storeIntermediateResults(double time) {
		for (Integer nodeNr : activeLineages) {
			final int daughterIndex1 = activeLineages.indexOf(nodeNr);// .getNr());
			double[] probs = new double[states];
			for (int i = 0; i < states; i++)
				probs[i] = linProbs[daughterIndex1 * states + i];

			intermediateStateProbs.get(nodeNr).add(probs);
			intermediateTimes.get(nodeNr).add(time);
		}
	}

	@Override
	public void store() {
//		super.store();
	}


	@Override
	public void restore() {
//		super.restore();
	}

	@Override
	public void init(PrintStream out) {
        out.println("#NEXUS\n");
        out.println("Begin trees;\n");        
	}

    @Override
    public void log(final long sample, final PrintStream out) {
        calculateLogP();
        double maxHeight=-1.0;
        for (int i = 0; i < treeIntervals.treeInput.get().size();i++) {
        	maxHeight = Math.max(treeIntervals.treeInput.get().get(i).getRoot().getHeight()+
        			treeIntervals.offset[i] + treeIntervals.rootLengthInput.get().get(i).getValue(), 
        			maxHeight);
        }
        String tree_string = "rem";
		BranchRateModel.Base branchRateModel = clockModelInput.get();
		List<Function> metadata = parameterInput.get();
		for (int i = 0; i < metadata.size(); i++) {
			if (metadata.get(i) instanceof StateNode) {
				metadata.set(i, ((StateNode) metadata.get(i)).getCurrent());
			}
		}

        for (int i = 0; i < treeIntervals.treeInput.get().size();i++) {
        	if (treeIntervals.treeInput.get().get(i).getExternalNodes().size()>=minClusterSizeInput.get())
	        	tree_string = tree_string + ",(" + 
    			toNewick(mappedTrees.get(i).getRoot(), metadata, branchRateModel) + 
		        ")[&obs=0]:" +  (maxHeight-treeIntervals.treeInput.get().get(i).getRoot().getHeight()-treeIntervals.offset[i]-treeIntervals.rootLengthInput.get().get(i).getValue());
        }
        tree_string = tree_string.replace("rem,", "(");
        tree_string = tree_string + "):0.0";
        tree_string = tree_string.replace("[&]", "");
        
        
        out.print("tree STATE_" + sample + " = ");
        out.print(tree_string);
        out.print(";");	        		
    }

//	@Override
//	public void log(long nSample, PrintStream out) {
//		calcForLogging(nSample);
//
//		List<Function> metadata = parameterInput.get();
//		for (int i = 0; i < metadata.size(); i++) {
//			if (metadata.get(i) instanceof StateNode) {
//				metadata.set(i, ((StateNode) metadata.get(i)).getCurrent());
//			}
//		}
//		
//		BranchRateModel.Base branchRateModel = clockModelInput.get();
//		// write out the log tree with meta data
//		out.print("tree STATE_" + nSample + " = ");
//		root = mappedTree.getRoot();
//		if (internalNodesOnlyInput.get())
//			out.print(toNewick(root, metadata, branchRateModel, root.getHeight()));
//		else
//			out.print(toNewick(root, metadata, branchRateModel));
//		out.print(";");
//	}

	Node root;

	/**
	 * Appends a double to the given StringBuffer, formatting it using the private
	 * DecimalFormat instance, if the input 'dp' has been given a non-negative
	 * integer, otherwise just uses default formatting.
	 * 
	 * @param buf
	 * @param d
	 */
	void appendDouble(StringBuffer buf, double d) {
		if (df == null) {
			buf.append(d);
		} else {
			buf.append(df.format(d));
		}
	}


	public String toNewick(Node node, List<Function> metadataList, BranchRateModel.Base branchRateModel) {
		StringBuffer buf = new StringBuffer();
		if (node.getLeft() != null) {
			buf.append("(");
			buf.append(toNewick(node.getLeft(), metadataList, branchRateModel));
			if (node.getRight() != null) {
				buf.append(',');
				buf.append(toNewick(node.getRight(), metadataList, branchRateModel));
			}
			buf.append(")");
		} else {
			if (!node.isLeaf())
				buf.append(node.getNr() + 1);
		}
		if (!node.isLeaf()) {
			
			if (!node.isRoot()) {
				buf.append("[&obs=1,");
				buf.append(dynamics.typeTraitInput.getName() + "=" + dynamics.getStringStateValue((int) node.getMetaData("location")));
			}else {
				buf.append("[&obs=0");
			}
			
		
			if (branchRateModel != null) {
				buf.append(",rate=");
				appendDouble(buf, branchRateModel.getRateForBranch(node));
			}
			buf.append(']');


		} else {
			
            buf.append(node.getID() + "[&obs=1,");

			
			if ( node.getMetaData("location")!=null) {
				buf.append(dynamics.typeTraitInput.getName() + "="
						+ dynamics.getStringStateValue((int) node.getMetaData("location")));
				buf.append(']');
			}

		}

		buf.append(":");
		if (substitutions) {
			appendDouble(buf, node.getLength() * branchRateModel.getRateForBranch(node));
		} else {
			appendDouble(buf, node.getLength());
		}
		return buf.toString();
	}
	
	public String toNewick(Node node, List<Function> metadataList, BranchRateModel.Base branchRateModel, double lastHeight) {
		StringBuffer buf = new StringBuffer();
		if (node.getLeft() != null) {
			if(node.getChildCount()==2) {
				buf.append("(");
				buf.append(toNewick(node.getLeft(), metadataList, branchRateModel, node.getHeight()));
				if (node.getRight() != null) {
					buf.append(',');
					buf.append(toNewick(node.getRight(), metadataList, branchRateModel, node.getHeight()));
				}
				buf.append(")");
			}else {
				buf.append(toNewick(node.getLeft(), metadataList, branchRateModel, lastHeight));
			}
		} else {
			if (!node.isLeaf())
				buf.append(node.getNr() + 1);
		}
		
		if(node.getChildCount()!=1) {

			if (!node.isLeaf()) {
				buf.append("[&obs=1,");
				buf.append(dynamics.typeTraitInput.getName() + "=" + dynamics.getStringStateValue((int) node.getMetaData("location")));
				
			
				if (branchRateModel != null) {
					buf.append(",rate=");
					appendDouble(buf, branchRateModel.getRateForBranch(node));
				}
				buf.append(']');
	
	
			} else {
	            buf.append(node.getID() + "[&obs=1,");
				
				if ( node.getMetaData("location")!=null) {
					buf.append(dynamics.typeTraitInput.getName() + "="
							+ dynamics.getStringStateValue((int) node.getMetaData("location")));
					buf.append(']');
				}
	
			}
	
			buf.append(":");
			if (substitutions) {
				appendDouble(buf, (lastHeight-node.getHeight()) * branchRateModel.getRateForBranch(node));
			} else {
				appendDouble(buf, lastHeight-node.getHeight());
			}
		}
		return buf.toString();
	}


//	public Node getRoot(){
//		return mappedTree.getRoot();
//	}
//	
//	@Override
//	public void close(PrintStream out) {
//		mappedTree.close(out);
//	}
//
//	public int getNodeState(int nr) {
//		return (int) mappedTree.getNode(nr).getMetaData("location");
//	}

}
