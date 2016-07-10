package com.jeffheaton.dissertation.experiments.payloads;

import com.jeffheaton.dissertation.experiments.data.DataCacheElement;
import com.jeffheaton.dissertation.experiments.data.ExperimentDatasets;
import com.jeffheaton.dissertation.experiments.manager.ExperimentTask;
import com.jeffheaton.dissertation.util.NewSimpleEarlyStoppingStrategy;
import com.jeffheaton.dissertation.util.QuickEncodeDataset;
import com.jeffheaton.dissertation.util.Transform;
import org.encog.EncogError;
import org.encog.mathutil.error.ErrorCalculation;
import org.encog.mathutil.error.ErrorCalculationMode;
import org.encog.mathutil.randomize.generate.GenerateRandom;
import org.encog.mathutil.randomize.generate.MersenneTwisterGenerateRandom;
import org.encog.ml.data.MLData;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.ea.score.adjust.ComplexityAdjustedScore;
import org.encog.ml.ea.train.basic.TrainEA;
import org.encog.ml.fitness.MultiObjectiveFitness;
import org.encog.ml.prg.EncogProgram;
import org.encog.ml.prg.EncogProgramContext;
import org.encog.ml.prg.PrgCODEC;
import org.encog.ml.prg.extension.FunctionFactory;
import org.encog.ml.prg.extension.StandardExtensions;
import org.encog.ml.prg.generator.RampedHalfAndHalf;
import org.encog.ml.prg.opp.ConstMutation;
import org.encog.ml.prg.opp.SubtreeCrossover;
import org.encog.ml.prg.opp.SubtreeMutation;
import org.encog.ml.prg.species.PrgSpeciation;
import org.encog.ml.prg.train.PrgPopulation;
import org.encog.ml.prg.train.rewrite.RewriteAlgebraic;
import org.encog.ml.prg.train.rewrite.RewriteConstants;
import org.encog.neural.networks.training.TrainingSetScore;
import org.encog.util.Format;
import org.encog.util.Stopwatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Created by jeff on 6/12/16.
 */
public class PayloadGeneticFit extends AbstractExperimentPayload {

    public static String GP_CLASS_ERROR = "GP cannot be used with multiple outputs (classification with more than 2 values)";
    public static final int POPULATION_SIZE = 100;
    private final List<EncogProgram> best = new ArrayList<>();
    private int n = 1;
    private FunctionFactory factory;
    private double globalError;
    private int totalIterations;

    private void verboseStatusGeneticProgram(ExperimentTask task, TrainEA genetic, EncogProgram best, NewSimpleEarlyStoppingStrategy earlyStop, PrgPopulation pop) {
        StringBuilder line = new StringBuilder();

        line.append("Epoch #");
        line.append(genetic.getIteration());
        line.append(" Train Error:");
        line.append(Format.formatDouble(genetic.getError(), 6));
        line.append(", Validation Error: ");
        line.append(Format.formatDouble(earlyStop.getValidationError(), 6));
        line.append(",Best Genome Size:");
        line.append(best.size());
        line.append(",Species Count:");
        line.append(pop.getSpecies().size());
        line.append(",best: ");
        line.append(best.dumpAsCommonExpression());
        line.append(", Stagnant: ");
        line.append(earlyStop.getStagnantIterations());
        task.log(line.toString());

        if( isVerbose() ) {
            System.out.println(line.toString());
        }
    }

    @Override
    public PayloadReport run(ExperimentTask task) {
        DataCacheElement cache = ExperimentDatasets.getInstance().loadDatasetGP(task);
        QuickEncodeDataset quick = cache.getQuick();
        MLDataSet dataset = cache.getData();

        if(dataset.getIdealSize()>2) {
            throw new EncogError(PayloadGeneticFit.GP_CLASS_ERROR);
        }

        Stopwatch sw = new Stopwatch();
        ErrorCalculation.setMode(ErrorCalculationMode.RMS);
        sw.start();
        // split
        GenerateRandom rnd = new MersenneTwisterGenerateRandom(42);
        org.encog.ml.data.MLDataSet[] split = Transform.splitTrainValidate(dataset, rnd, 0.75);
        MLDataSet trainingSet = split[0];
        MLDataSet validationSet = split[1];

        EncogProgramContext context = new EncogProgramContext();
        for (String field: quick.getFieldNames()) {
            context.defineVariable(field);
        }

        FunctionFactory factory = context.getFunctions();
        factory.addExtension(StandardExtensions.EXTENSION_VAR_SUPPORT);
        factory.addExtension(StandardExtensions.EXTENSION_CONST_SUPPORT);
        factory.addExtension(StandardExtensions.EXTENSION_NEG);
        factory.addExtension(StandardExtensions.EXTENSION_ADD);
        factory.addExtension(StandardExtensions.EXTENSION_SUB);
        factory.addExtension(StandardExtensions.EXTENSION_MUL);
        factory.addExtension(StandardExtensions.EXTENSION_DIV);
        factory.addExtension(StandardExtensions.EXTENSION_POWER);

        this.totalIterations = 0;
        this.globalError = 0;
        this.best.clear();

        for(int i=0;i<this.n;i++) {
            fitOne(task, context,trainingSet,validationSet);
        }

        sw.stop();

        return new PayloadReport(
                (int) (sw.getElapsedMilliseconds() / 1000),this.globalError/this.n,
                this.totalIterations,this.best.get(0).dumpAsCommonExpression());
    }

    private void fitOne(ExperimentTask task, EncogProgramContext context, MLDataSet trainingSet, MLDataSet validationSet) {

        PrgPopulation pop = new PrgPopulation(context, POPULATION_SIZE);

        MultiObjectiveFitness score = new MultiObjectiveFitness();
        score.addObjective(1.0, new TrainingSetScore(trainingSet));

        TrainEA genetic = new TrainEA(pop, score);
        genetic.setCODEC(new PrgCODEC());
        genetic.addOperation(0.5, new SubtreeCrossover());
        genetic.addOperation(0.25, new ConstMutation(context, 0.5, 1.0));
        genetic.addOperation(0.25, new SubtreeMutation(context, 4));
        genetic.addScoreAdjuster(new ComplexityAdjustedScore(10, 20, 50, 100.0));
        genetic.getRules().addRewriteRule(new RewriteConstants());
        genetic.getRules().addRewriteRule(new RewriteAlgebraic());
        genetic.setSpeciation(new PrgSpeciation());
        genetic.setThreadCount(1);

        NewSimpleEarlyStoppingStrategy earlyStop = new NewSimpleEarlyStoppingStrategy(validationSet, 5, 50, 0.01);
        genetic.addStrategy(earlyStop);

        (new RampedHalfAndHalf(context, 1, 6)).generate(new Random(), pop);

        genetic.setShouldIgnoreExceptions(false);

        long lastUpdate = System.currentTimeMillis();
        int populationFails = 0;


        do {
            long sinceLastUpdate = (System.currentTimeMillis() - lastUpdate) / 1000;

            if (pop.getSpecies().size() == 0) {
                populationFails++;

                (new RampedHalfAndHalf(context, 1, 6)).generate(new Random(), pop);
                if (populationFails >= 5) {
                    throw new EncogError("Entire population invalid after 5 regenerations: ");
                }
            }

            genetic.iteration();
            EncogProgram currentBest = (EncogProgram) genetic.getBestGenome();
            if (isVerbose() || genetic.getIteration() == 1 || genetic.isTrainingDone() || sinceLastUpdate > 60) {
                verboseStatusGeneticProgram(task, genetic, currentBest, earlyStop, pop);
            }
        } while (!genetic.isTrainingDone());

        this.totalIterations += genetic.getIteration();
        this.globalError += earlyStop.getValidationError();
        EncogProgram prg = (EncogProgram) genetic.getBestGenome();
        prg.setPopulation(null);
        this.best.add(prg);
        pop.clear();

    }

    public List<EncogProgram> getBest() {
        return this.best;
    }

    public int getN() {
        return n;
    }

    public void setN(int n) {
        this.n = n;
    }
}
