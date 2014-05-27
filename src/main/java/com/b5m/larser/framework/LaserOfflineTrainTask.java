package com.b5m.larser.framework;

import java.io.IOException;

import org.apache.hadoop.fs.Path;
import org.apache.mahout.common.HadoopUtil;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.b5m.HDFSHelper;
import com.b5m.admm.AdmmOptimizerDriver;
import com.b5m.conf.Configuration;
import com.b5m.larser.feature.LaserMessageConsumer;
import com.b5m.larser.feature.offline.OfflineFeatureDriver;
import com.b5m.larser.offline.topn.LaserOfflineResultWriter;
import com.b5m.larser.offline.topn.LaserOfflineTopNDriver;

public class LaserOfflineTrainTask implements Job {
	private static final Logger LOG = LoggerFactory
			.getLogger(LaserOfflineTrainTask.class);

	public void execute(JobExecutionContext context)
			throws JobExecutionException {
		String collection = context.getJobDetail().getGroup();

		final Path outputPath = Configuration.getInstance()
				.getLaserOfflineOutput(collection);
		final Integer iterationsMaximum = Configuration.getInstance()
				.getMaxIteration(collection);
		final Float regularizationFactor = Configuration.getInstance()
				.getRegularizationFactor(collection);
		final Boolean addIntercept = Configuration.getInstance().addIntercept(
				collection);
		final org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
		;
		conf.set("mapred.job.queue.name", "sf1");
		try {
			final LaserMessageConsumer consumeTask = LaserMessageConsumeTask
					.getInstance().getLaserMessageConsumer(collection);
			long majorVersion = consumeTask.getMajorVersion();

			consumeTask.incrMajorVersion();
			LOG.info("Update MetaQ's output path, major version from {} to {}",
					majorVersion, consumeTask.getMajorVersion());

			Path input = new Path(Configuration.getInstance().getMetaqOutput(
					collection), Long.toString(majorVersion) + "-*");
			LOG.info("Retraining Laser's Offline Model, result = {}",
					outputPath);

			Path signalData = new Path(outputPath, "ADMM_SIGNAL");
			OfflineFeatureDriver.run(input, signalData, conf);

			LOG.info("Deleting files: {}", input);
			HDFSHelper.deleteFiles(input.getParent(), input.getName(),
					input.getFileSystem(conf));

			Path admmOutput = new Path(outputPath, "ADMM");
			AdmmOptimizerDriver.run(signalData, admmOutput,
					regularizationFactor, addIntercept, null,
					iterationsMaximum, conf);
			HadoopUtil.delete(conf, signalData);

			LaserOfflineResultWriter writer = new LaserOfflineResultWriter();
			writer.write(
					conf,
					outputPath.getFileSystem(conf),
					new Path(admmOutput, AdmmOptimizerDriver.FINAL_MODEL),
					outputPath,
					Configuration.getInstance().getUserFeatureDimension(
							collection), Configuration.getInstance()
							.getItemFeatureDimension(collection));

			LOG.info("calculating offline topn clusters for each user, write results to msgpack");
			LaserOfflineTopNDriver.run(collection, Configuration.getInstance()
					.getTopNClustering(collection), conf);

		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
