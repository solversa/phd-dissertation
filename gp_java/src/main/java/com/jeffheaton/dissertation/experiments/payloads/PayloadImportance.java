package com.jeffheaton.dissertation.experiments.payloads;

import com.jeffheaton.dissertation.JeffDissertation;
import com.jeffheaton.dissertation.experiments.data.ExperimentDatasets;
import com.jeffheaton.dissertation.experiments.manager.ExperimentTask;
import org.encog.engine.network.activation.ActivationLinear;
import org.encog.engine.network.activation.ActivationReLU;
import org.encog.engine.network.activation.ActivationSoftMax;
import org.encog.mathutil.error.ErrorCalculation;
import org.encog.mathutil.error.ErrorCalculationMode;
import org.encog.mathutil.error.NormalizedError;
import org.encog.mathutil.randomize.XaiverRandomizer;
import org.encog.mathutil.randomize.generate.GenerateRandom;
import org.encog.mathutil.randomize.generate.MersenneTwisterGenerateRandom;
import org.encog.ml.MLRegression;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.importance.FeatureImportance;
import org.encog.ml.importance.FeatureRank;
import org.encog.ml.importance.NeuralFeatureImportanceCalc;
import org.encog.ml.importance.PerturbationFeatureImportanceCalc;
import org.encog.ml.train.MLTrain;
import org.encog.ml.train.strategy.end.EarlyStoppingStrategy;
import org.encog.neural.networks.BasicNetwork;
import org.encog.neural.networks.layers.BasicLayer;
import org.encog.neural.networks.training.propagation.sgd.StochasticGradientDescent;
import org.encog.neural.networks.training.propagation.sgd.update.AdaGradUpdate;
import org.encog.util.EngineArray;
import org.encog.util.Format;
import org.encog.util.Stopwatch;
import org.encog.util.simple.EncogUtility;

public class PayloadImportance extends AbstractExperimentPayload {

    private String permRanking;
    private String networkRanking;
    private int permRankingStable;
    private int networkRankingStable;

    private void verboseStatusImportance(ExperimentTask task, MLTrain train, EarlyStoppingStrategy earlyStop) {
        task.log("Epoch #" + train.getIteration() + " Train Error:"
                    + Format.formatDouble(train.getError(), 6) + ", Perm("+this.permRankingStable+"):"
                    + this.permRanking + ", Weight("+this.networkRankingStable+"):" + this.networkRanking
                    + ", Validation Error: " + Format.formatDouble(earlyStop.getValidationError(), 6) +
                    ", Stagnant: " + earlyStop.getStagnantIterations());
    }

    private String calculateImportanceType(BasicNetwork network, MLDataSet validationSet, FeatureImportance fi) {
        fi.init(network,null);
        fi.performRanking(validationSet);
        return fi.toString();
    }

    private void calculateImportance(BasicNetwork network, MLDataSet validationSet) {
        String currentPermRanking = calculateImportanceType(network,validationSet,new PerturbationFeatureImportanceCalc());
        String currentWeightRanking = calculateImportanceType(network,validationSet,new NeuralFeatureImportanceCalc());

        if( currentPermRanking.equals(this.permRanking) ) {
            this.permRankingStable ++;
        } else {
            this.permRankingStable = 0;
        }

        if( currentWeightRanking.equals(this.networkRanking) ) {
            this.networkRankingStable ++;
        } else {
            this.networkRankingStable = 0;
        }

        this.permRanking = currentPermRanking;
        this.networkRanking = currentWeightRanking;
    }

    @Override
    public PayloadReport run(ExperimentTask task) {
        ErrorCalculation.setMode(ErrorCalculationMode.RMS);

        Stopwatch sw = new Stopwatch();
        sw.start();
        MLDataSet dataset = ExperimentDatasets.getInstance().loadDatasetNeural(
                task.getDatasetFilename(),
                task.getModelType().getTarget(),
                EngineArray.string2list(task.getPredictors())).getData();

        // split
        GenerateRandom rnd = new MersenneTwisterGenerateRandom(42);
        org.encog.ml.data.MLDataSet[] split = EncogUtility.splitTrainValidate(dataset, rnd, 0.75);
        MLDataSet trainingSet = split[0];
        MLDataSet validationSet = split[1];

        // create a neural network, without using a factory
        BasicNetwork network = new BasicNetwork();
        network.addLayer(new BasicLayer(null, true, trainingSet.getInputSize()));
        network.addLayer(new BasicLayer(new ActivationReLU(), true, 200));
        network.addLayer(new BasicLayer(new ActivationReLU(), true, 100));
        network.addLayer(new BasicLayer(new ActivationReLU(), true, 25));

        if (task.getModelType().isRegression()) {
            network.addLayer(new BasicLayer(new ActivationLinear(), false, trainingSet.getIdealSize()));
        } else {
            network.addLayer(new BasicLayer(new ActivationSoftMax(), false, trainingSet.getIdealSize()));
        }
        network.getStructure().finalizeStructure();
        (new XaiverRandomizer()).randomize(network);
        //network.reset();

        // train the neural network
        StochasticGradientDescent train = new StochasticGradientDescent(network, trainingSet);
        train.setUpdateRule(new AdaGradUpdate());
        train.setBatchSize(JeffDissertation.MINI_BATCH_SIZE);
        train.setL1(JeffDissertation.L1);
        train.setL2(JeffDissertation.L2);
        train.setLearningRate(JeffDissertation.LEARNING_RATE);

        EarlyStoppingStrategy earlyStop = new EarlyStoppingStrategy(validationSet, 10, JeffDissertation.STAGNANT_NEURAL);
        earlyStop.setSaveBest(true);
        train.addStrategy(earlyStop);

        long lastUpdate = System.currentTimeMillis();

        do {
            train.iteration();
            calculateImportance(network,validationSet);

            long sinceLastUpdate = (System.currentTimeMillis() - lastUpdate) / 1000;

            if (isVerbose() || train.getIteration() == 1 || train.isTrainingDone() || sinceLastUpdate > 60) {
                verboseStatusImportance(task, train, earlyStop);
                lastUpdate = System.currentTimeMillis();
            }

            if (Double.isNaN(train.getError()) || Double.isInfinite(train.getError())) {
                break;
            }

        } while (!train.isTrainingDone());
        train.finishTraining();

        if(isVerbose()) {
            System.out.println("Feature importance (permutation)");
            FeatureImportance fi = new PerturbationFeatureImportanceCalc(); //new NeuralFeatureImportanceCalc();
            fi.init(network,null);
            fi.performRanking(trainingSet);

            for (FeatureRank ranking : fi.getFeaturesSorted()) {
                System.out.println(ranking.toString());
            }
            System.out.println(fi.toString());

            System.out.println();
            System.out.println("Feature importance (weights)");
            fi = new NeuralFeatureImportanceCalc();
            fi.init(network,null);
            fi.performRanking(validationSet);

            for (FeatureRank ranking : fi.getFeaturesSorted()) {
                System.out.println(ranking.toString());
            }
            System.out.println(fi.toString());
        }
        NormalizedError error = new NormalizedError(validationSet);
        MLRegression bestNetwork = earlyStop.getBestModel()==null?network:earlyStop.getBestModel();
        double normalizedError = error.calculateNormalizedMean(validationSet,bestNetwork);


        sw.stop();
        return new PayloadReport(
                (int) (sw.getElapsedMilliseconds() / 1000),
                normalizedError, earlyStop.getValidationError(), this.networkRankingStable, this.permRankingStable,
                train.getIteration(), "");
    }
}
