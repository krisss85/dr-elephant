package com.linkedin.drelephant;

import com.linkedin.drelephant.analysis.Constants;
import com.linkedin.drelephant.analysis.HeuristicResult;
import com.linkedin.drelephant.analysis.Severity;
import com.linkedin.drelephant.hadoop.HadoopJobData;
import model.JobHeuristicResult;
import model.JobResult;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.JobStatus;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class ElephantRunner implements Runnable {
    private static final long WAIT_INTERVAL = 5 * 60 * 1000;
    private static final Logger logger = Logger.getLogger(ElephantRunner.class);
    private AtomicBoolean running = new AtomicBoolean(true);
    private boolean firstRun = true;

    @Override
    public void run() {
        Constants.load();
        try {
            ElephantFetcher fetcher = new ElephantFetcher();
            Set<JobID> previousJobs = new HashSet<JobID>();
            long lastRun;

            while (running.get()) {
                lastRun = System.currentTimeMillis();

                try {
                    logger.info("Fetching job list.");
                    JobStatus[] jobs = fetcher.getJobList();
                    if (jobs == null) {
                        throw new IllegalArgumentException("Jobtracker returned 'null' for job list");
                    }

                    Set<JobID> successJobs = filterSuccessfulJobs(jobs);

                    successJobs = filterPreviousJobs(successJobs, previousJobs);

                    logger.info(successJobs.size() + " jobs to analyse.");

                    //Analyse all ready jobs
                    for (JobID jobId : successJobs) {
                        try {
                            analyzeJob(fetcher, jobId);
                            previousJobs.add(jobId);
                        } catch (Exception e) {
                            logger.error("Error analysing job", e);
                        }
                    }
                    logger.info("Finished all jobs. Waiting for refresh.");

                } catch (Exception e) {
                    logger.error("Error getting job list", e);
                }

                //Wait for long enough
                long nextRun = lastRun + WAIT_INTERVAL;
                long waitTime = nextRun - System.currentTimeMillis();
                while (running.get() && waitTime > 0) {
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    waitTime = nextRun - System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            logger.error("Error in ElephantRunner", e);
        }
    }

    private Set<JobID> filterSuccessfulJobs(JobStatus[] jobs) {
        Set<JobID> successJobs = new HashSet<JobID>();
        for (JobStatus job : jobs) {
            if (job.getRunState() == JobStatus.SUCCEEDED && job.isJobComplete()) {
                successJobs.add(job.getJobID());
            }
        }
        return successJobs;
    }

    private Set<JobID> filterPreviousJobs(Set<JobID> jobs, Set<JobID> previousJobs) {
        logger.info("Cleaning up previous runs.");
        //On first run, check against DB
        if (firstRun) {
            Set<JobID> newJobs = new HashSet<JobID>();
            for (JobID jobId : jobs) {
                JobResult prevResult = JobResult.find.byId(jobId.toString());
                if (prevResult == null) {
                    //Job not found, add to new jobs list
                    newJobs.add(jobId);
                } else {
                    //Job found, add to old jobs list
                    previousJobs.add(jobId);
                }
            }
            jobs = newJobs;
            firstRun = false;
        } else {
            //Remove untracked jobs
            previousJobs.retainAll(jobs);
            //Remove previously analysed jobs
            jobs.removeAll(previousJobs);
        }

        return jobs;
    }

    private void analyzeJob(ElephantFetcher fetcher, JobID jobId) throws Exception {
        logger.info("Looking at job " + jobId);
        HadoopJobData jobData = fetcher.getJobData(jobId);

        //Job wiped from jobtracker already.
        if (jobData == null) {
            return;
        }

        HeuristicResult[] analysisResults = ElephantAnalyser.instance().analyse(jobData);

        //Save to DB
        JobResult result = new JobResult();
        result.job_id = jobId.toString();
        result.url = jobData.getUrl();
        result.username = jobData.getUsername();
        result.startTime = jobData.getStartTime();
        result.analysisTime = System.currentTimeMillis();
        result.jobName = jobData.getJobName();

        //Truncate long names
        if (result.jobName.length() > 100) {
            result.jobName = result.jobName.substring(0, 97) + "...";
        }
        result.heuristicResults = new ArrayList<JobHeuristicResult>();

        Severity worstSeverity = Severity.NONE;

        for (HeuristicResult heuristicResult : analysisResults) {
            JobHeuristicResult detail = new JobHeuristicResult();
            detail.analysisName = heuristicResult.getAnalysis();
            detail.data = heuristicResult.getDetailsCSV();
            detail.dataColumns = heuristicResult.getDetailsColumns();
            detail.severity = heuristicResult.getSeverity();
            if (detail.dataColumns < 1) {
                detail.dataColumns = 1;
            }
            result.heuristicResults.add(detail);
            worstSeverity = Severity.max(worstSeverity, detail.severity);
        }

        result.severity = worstSeverity;

        result.save();
    }

    public void kill() {
        running.set(false);
    }
}
