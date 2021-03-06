package com.jeffheaton.dissertation.experiments.data;

import com.jeffheaton.dissertation.experiments.manager.ExperimentTask;
import com.jeffheaton.dissertation.experiments.payloads.ExperimentPayload;
import com.jeffheaton.dissertation.util.QuickEncodeDataset;
import org.encog.EncogError;
import org.encog.ml.data.MLDataSet;

public class DataCacheElement {

    private QuickEncodeDataset quick;
    private MLDataSet data;
    private MLDataSet commonProcessing;

    public DataCacheElement(QuickEncodeDataset theQuick) {
        this.quick = theQuick;
    }

    public MLDataSet getData() {
        if( this.data==null ) {
            this.data = this.quick.generateDataset();
        }

        return this.data;
    }

    public QuickEncodeDataset getQuick() {
        return this.quick;
    }

    public synchronized MLDataSet obtainCommonProcessing(ExperimentTask task, ExperimentPayload payload) {
        try {
            task.log("Requested common: " + task);
            if (this.commonProcessing == null) {
                task.log("Loading common: " + task);
                this.commonProcessing = payload.obtainCommonProcessing(task);
            }
            task.log("Got common processing element.");
            return this.commonProcessing;
        } catch (Exception ex) {
            System.out.println("Error!!");
            task.log("Error");
            task.log(ex);
            ex.printStackTrace();
            throw(new EncogError(ex));
        } finally {
            task.log("finally: " + this.commonProcessing);
        }
    }
}
