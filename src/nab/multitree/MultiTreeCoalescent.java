package nab.multitree;


import java.util.Collections;
import java.util.List;
import java.util.Random;

import beast.core.CalculationNode;
import beast.core.Description;
import beast.core.Input;
import beast.core.Input.Validate;
import beast.core.State;
import beast.evolution.tree.coalescent.IntervalList;
import beast.evolution.tree.coalescent.IntervalType;
import beast.evolution.tree.coalescent.PopulationFunction;
import beast.math.Binomial;
import nab.skygrid.RateMultiplier;
import nab.skygrid.TimeVaryingRates;


/**
 * @author Nicola F. Mueller adapted from Alexei Drummond
 */

@Description("Calculates the probability of a beast.tree conditional on a population size function. " +
        "Note that this does not take the number of possible tree interval/tree topology combinations " +
        "in account, in other words, the constant required for making this a proper distribution that integrates " +
        "to unity is not calculated (partly, because we don't know how for sequentially sampled data).")
public class MultiTreeCoalescent extends MultiTreeDistribution {
	
    final public Input<PopulationFunction> popSizeInput = new Input<>("populationModel", 
    		"A population size model", Validate.REQUIRED);
    
    final public Input<TimeVaryingRates> immigrationRateInput = new Input<>("immigrationRate", 
    		"A population size model", Validate.REQUIRED);
    
    final public Input<RateMultiplier> rateMultiplierInput = new Input<>("rateMultiplier", 
    		"A population size model", Validate.OPTIONAL);
    
    final public Input<TimeVaryingRates> samplingRateInput = new Input<>("samplingRate", 
    		"sampling rate as a rate relative to the population size", Validate.OPTIONAL);


    final public Input<Boolean> rateIsBackwardsInput = new Input<>("rateIsBackwards", "define whether the rate is backwards in time", true);

    
    MultiTreeIntervals intervals;
    TimeVaryingRates immigrationRate;
    TimeVaryingRates samplingRate;
    RateMultiplier rateMulitplier;
    boolean hasRateMultiplier = false;
    boolean hasSamplingRate = false;
    
    boolean allDirty = false;
    
    
    @Override
    public void initAndValidate() {
        intervals = multiTreeIntervalsInput.get();
        if (intervals == null) {
            throw new IllegalArgumentException("Expected treeIntervals to be specified");
        }
        immigrationRate = immigrationRateInput.get();
        if (rateMultiplierInput.get()!=null) {
        	rateMulitplier = rateMultiplierInput.get();
        	hasRateMultiplier = true;
        }
        
        if (samplingRateInput.get() != null) {
        	samplingRate = samplingRateInput.get();
        	hasSamplingRate = true;
        }
        	
        
        calculateLogP();
    }

    /**
     * do the actual calculation *
     */
    @Override
    public double calculateLogP() {

        logP = calculateLogLikelihood(intervals, popSizeInput.get());

        if (Double.isInfinite(logP)) {
        	logP = Double.NEGATIVE_INFINITY;
        }

        return logP;
    }

    @Override
    public void sample(State state, Random random) {
        // TODO this should eventually sample a coalescent tree conditional on population size function
        throw new UnsupportedOperationException("This should eventually sample a coalescent tree conditional on population size function.");
    }

    /**
     * @return a list of unique ids for the state nodes that form the argument
     */
    @Override
    public List<String> getArguments() {
        return Collections.singletonList(multiTreeIntervalsInput.get().getID());
    }

    /**
     * @return a list of unique ids for the state nodes that make up the conditions
     */
    @Override
    public List<String> getConditions() {
        return popSizeInput.get().getParameterIds();
    }


    /**
     * Calculates the log likelihood of this set of coalescent intervals,
     * given a demographic model.
     *
     * @param intervals       the intervals whose likelihood is computed
     * @param popSizeFunction the population size function
     * @return the log likelihood of the intervals given the population size function
     */
    public double calculateLogLikelihood(IntervalList intervals, PopulationFunction popSizeFunction) {
        return calculateLogLikelihood(intervals, popSizeFunction, 0.0);
    }

    /**
     * Calculates the log likelihood of this set of coalescent intervals,
     * given a population size function.
     *
     * @param intervals       the intervals whose likelihood is computed
     * @param popSizeFunction the population size function
     * @param threshold       the minimum allowable coalescent interval size; negative infinity will be returned if
     *                        any non-zero intervals are smaller than this
     * @return the log likelihood of the intervals given the population size function
     */
    public double calculateLogLikelihood(IntervalList intervals, PopulationFunction popSizeFunction, double threshold) {

        double logL = 0.0;

        double startTime = 0.0;
        final int n = intervals.getIntervalCount();
        for (int i = 0; i < n; i++) {

            final double duration = intervals.getInterval(i);
            final double finishTime = startTime + duration;

            final double intervalArea = popSizeFunction.getIntegral(startTime, finishTime);///Math.exp(rateMulitplier.getMeanRate(startTime, finishTime));
            if (intervalArea == 0 && duration > 1e-10) {
            	/* the above test used to be duration != 0, but that leads to numerical issues on resume
            	 * (https://github.com/CompEvol/beast2/issues/329) */
                return Double.NEGATIVE_INFINITY;
            }
            final int lineageCount = intervals.getLineageCount(i);

            final double kChoose2 = Binomial.choose2(lineageCount);
            
            // compute the mean migration rate
            
            double meanMig;
            if (hasRateMultiplier)
            	meanMig = Math.exp(rateMulitplier.getMeanRate(startTime, finishTime) + immigrationRate.getMeanRate(startTime, finishTime));
        	else
        		meanMig = Math.exp(immigrationRate.getMeanRate(startTime, finishTime));
            
            // coalescent part
            logL -= kChoose2 * intervalArea;
            if (rateIsBackwardsInput.get())
            	logL -= meanMig * lineageCount * duration;
            else
            	logL -= meanMig * lineageCount * intervalArea;
            
            
            if (intervals.getIntervalType(i) == IntervalType.COALESCENT) {

                final double demographicAtCoalPoint = popSizeFunction.getPopSize(finishTime);//*Math.exp(rateMulitplier.getRate(finishTime));

                // if value at end is many orders of magnitude different than mean over interval reject the interval
                // This is protection against cases where ridiculous infinitesimal
                // population size at the end of a linear interval drive coalescent values to infinity.

                if (duration == 0.0 || demographicAtCoalPoint * (intervalArea / duration) >= threshold) {
                    //                if( duration == 0.0 || demographicAtCoalPoint >= threshold * (duration/intervalArea) ) {
                    logL -= Math.log(demographicAtCoalPoint);
                } else {
                    // remove this at some stage
                    //  System.err.println("Warning: " + i + " " + demographicAtCoalPoint + " " + (intervalArea/duration) );
                    return Double.NEGATIVE_INFINITY;
                }
            }
            if (intervals.getIntervalType(i) == IntervalType.MIGRATION) {
            	double mig;
                if (hasRateMultiplier)
                	mig = Math.exp(rateMulitplier.getRate(finishTime)+immigrationRate.getRate(finishTime));
                else
                	mig = Math.exp(immigrationRate.getRate(finishTime));
                if (rateIsBackwardsInput.get())
                	logL += Math.log(mig);
                else
                	logL += Math.log(mig/popSizeFunction.getPopSize(finishTime));                
            }
            if (hasSamplingRate) {
            	if (intervals.getIntervalType(i) == IntervalType.SAMPLE) {
            		double sampling = Math.exp(samplingRate.getRate(finishTime)) * popSizeFunction.getPopSize(finishTime);
                    logL += Math.log(sampling);            		
            	}
            	if (intervalArea>0.0) {
            		double meanSampling = Math.exp(samplingRate.getMeanRate(startTime, finishTime)) * duration / intervalArea;
            		logL -= meanSampling * duration;
            	}
            }
            
            startTime = finishTime;
        }
        
        return logL;
    }

    @Override
    protected boolean requiresRecalculation() {
    	if (((CalculationNode) popSizeInput.get()).isDirtyCalculation() || immigrationRateInput.isDirty())
    		allDirty=true;
    	
        return ((CalculationNode) popSizeInput.get()).isDirtyCalculation() || super.requiresRecalculation();
    }
}
