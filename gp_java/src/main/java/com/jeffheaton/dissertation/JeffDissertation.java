package com.jeffheaton.dissertation;

import com.jeffheaton.dissertation.experiments.ex1.PerformExperiment1;
import com.jeffheaton.dissertation.experiments.misc.ExperimentAutoFeature;
import com.jeffheaton.dissertation.experiments.misc.ExperimentGPFile;
import com.jeffheaton.dissertation.experiments.misc.ExperimentNeuralXOR;
import com.jeffheaton.dissertation.experiments.misc.ExperimentSimpleGP;

public class JeffDissertation {
    public static void main(String[] args) {
        if( args[0].equalsIgnoreCase("neural-xor") ) {
            (new ExperimentNeuralXOR()).main(null);
        } else if( args[0].equalsIgnoreCase("simple-gp")) {
            (new ExperimentSimpleGP()).main(null);
        } else if( args[0].equalsIgnoreCase("feature-search")) {
            (new ExperimentAutoFeature()).main(null);
        } else if( args[0].equalsIgnoreCase("file-gp")) {
            (new ExperimentGPFile()).main(null);
        } else if( args[0].equalsIgnoreCase("experiment-1")) {
            (new PerformExperiment1()).main(null);
        }
    }
}
