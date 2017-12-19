/**
 *  Copyright 2007-2008 University Of Southern California
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package edu.isi.pegasus.planner.code.gridstart;

import edu.isi.pegasus.common.logging.LogManager;
import edu.isi.pegasus.planner.classes.ADag;
import edu.isi.pegasus.planner.classes.AggregatedJob;
import edu.isi.pegasus.planner.classes.Job;
import edu.isi.pegasus.planner.classes.PegasusBag;
import edu.isi.pegasus.planner.classes.PlannerOptions;
import edu.isi.pegasus.planner.cluster.aggregator.AWSBatch;
import edu.isi.pegasus.planner.code.GridStart;
import edu.isi.pegasus.planner.partitioner.graph.GraphNode;
import java.util.Iterator;

/**
 *
 * Wraps a job to be run via pegasus-aws-batch on the AWS Batch service.
 * 
 * This is achieved by first wrapping the job with PegasusLite and then running
 * the task using pegasus-aws-batch-launch.sh on a AWS Batch fetch and run
 * container.
 * 
 * @author Karan Vahi
 */
public class PegasusAWSBatchGS implements GridStart {
    
    private PegasusBag mBag;
    
    private ADag mDAG;
    
    /**
     * The basename of the class that is implmenting this. Could have
     * been determined by reflection.
     */
    public static final String CLASSNAME = "PegasusAWSBatchGS";

    /**
     * The SHORTNAME for this implementation.
     */
    public static final String SHORT_NAME = "pegasus-aws-batch";


    /**
     * The basename of the script used to launch jobs in the AWS Batch via
     * the fetch and run example
     */
    public static final String PEGASUS_AWS_BATCH_LAUNCH_BASENAME = "pegasus-aws-batch-launch.sh";

    public static final String SEPARATOR = "########################";
    public static final char SEPARATOR_CHAR = '#';
    public static final String  MESSAGE_PREFIX = "[Pegasus AWS Batch]";
    public static final int  MESSAGE_STRING_LENGTH = 80;
    
    /**
     * Instance to Pegasus Lite for wrapping jobs
     */
    private PegasusLite mPegasusLite;
    
    /**
     * The logging instance
     */
    private LogManager mLogger;
    private String mSubmitDir;
    
    public PegasusAWSBatchGS(){
        mPegasusLite = new PegasusLite();
    }

    /**
     * 
     * @param bag
     * @param dag 
     */
    public void initialize(PegasusBag bag, ADag dag) {
        mBag       = bag;
        mDAG       = dag;
        mLogger    = bag.getLogger();
        
        mSubmitDir = bag.getPlannerOptions().getSubmitDirectory();
        mPegasusLite.initialize(bag, dag);
    }

    /**
     * Enables a clustered job.
     * 
     * @param job
     * @param isGlobusJob
     * @return 
     */
    public boolean enable(AggregatedJob job, boolean isGlobusJob) {
        if( !job.getTXName().equals( AWSBatch.COLLAPSE_LOGICAL_NAME) ){
            throw new RuntimeException( "Aggregated job not clusted using AWSBatch - " + job.getTXName() );
        }
        
        boolean enable = true;
        String relativeDir = job.getRelativeSubmitDirectory();
        for( Iterator<GraphNode> it = job.nodeIterator(); it.hasNext();  ) {
            GraphNode node = it.next();
            Job constitutentJob = (Job) node.getContent();

            if( constitutentJob instanceof AggregatedJob ){
                //slurp in contents of it's stdin
                throw new RuntimeException( "Enabling of clustered jobs within a cluster not supported with " + PegasusAWSBatchGS.PEGASUS_AWS_BATCH_LAUNCH_BASENAME );
            }
            //we need to set the relative dir of constituent jobs to 
            //the clustered job itself
            constitutentJob.setRelativeSubmitDirectory( relativeDir );
            
            enable = enable && this.enable(constitutentJob, isGlobusJob);
        }
        
        //set up stdout and stderr for the clustered job
        construct(job,"output", job.getFileFullPath( mSubmitDir, ".out") );
        if (isGlobusJob) {
            construct(job,"transfer_output","true");
        }
        construct(job,"error", job.getFileFullPath( mSubmitDir, ".err") );
        if (isGlobusJob) {
            construct(job,"transfer_error","true");
        }
        
        return enable;
    }

    /**
     * Enables a single job to be launched via PegasusLite instance.
     * 
     * @param job
     * @param isGlobusJob
     * 
     * @return 
     */
    public boolean enable(Job job, boolean isGlobusJob) {
        return this.mPegasusLite.enable(job, isGlobusJob);
    }

    /**
     * Pass through to PegasusLite instance
     * @param fullPath 
     */
    public void useFullPathToGridStarts(boolean fullPath) {
        mPegasusLite.useFullPathToGridStarts(fullPath);
    }

    /**
     * Pass through to PegasusLite instance
     * 
     * @return boolean
     */
    public boolean canSetXBit() {
        return mPegasusLite.canSetXBit();
    }

     /**
     * Pass through to PegasusLite instance
     * 
     * @return String
     */
    public String getWorkerNodeDirectory(Job job) {
        return mPegasusLite.getWorkerNodeDirectory(job);
    }

   /**
     * Returns the value of the  profile with key as Pegasus.GRIDSTART_KEY,
     * that would result in the loading of this particular implementation.
     * It is usually the name of the implementing class without the
     * package name.
     *
     * @return the value of the profile key.
     */
    public  String getVDSKeyValue(){
        return PegasusAWSBatchGS.CLASSNAME;
    }


    /**
     * Returns a short textual description in the form of the name of the class.
     *
     * @return  short textual description.
     */
    public String shortDescribe(){
        return PegasusAWSBatchGS.SHORT_NAME;
    }

    /**
     * Returns the SHORT_NAME for the POSTScript implementation that is used
     * to be as default with this GridStart implementation.
     *
     * @return  the identifier for the default POSTScript implementation for
     *          kickstart gridstart module.
     *
     */
    public String defaultPOSTScript(){
        return mPegasusLite.defaultPOSTScript();
    }
 
    /**
     * Constructs a condor variable in the condor profile namespace
     * associated with the constituentJob. Overrides any preexisting key values.
     *
     * @param constituentJob   contains the constituentJob description.
     * @param key   the key of the profile.
     * @param value the associated value.
     */
    private void construct(Job job, String key, String value){
        job.condorVariables.construct(key,value);
    }

}