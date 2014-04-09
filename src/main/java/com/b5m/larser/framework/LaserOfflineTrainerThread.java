package com.b5m.larser.framework;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.hadoop.fs.Path;
import org.apache.mahout.common.HadoopUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.b5m.admm.AdmmOptimizerDriver;
import com.b5m.conf.Configuration;
import com.b5m.larser.feature.offline.OfflineFeatureDriver;
import com.b5m.larser.offline.LaserOfflineResultWriter;

public class LaserOfflineTrainerThread {
	private static final Logger LOG = LoggerFactory
			.getLogger(LaserOfflineTrainerThread.class);
	private static LaserOfflineTrainerThread thread = null;

	public static synchronized LaserOfflineTrainerThread getInstance() {
		if (null == thread) {
			thread = new LaserOfflineTrainerThread();
		}
		return thread;
	}

	private final Timer timer;

	private LaserOfflineTrainerThread() {
		timer = new Timer();
	}

	public void start() {
		Configuration conf = Configuration.getInstance();
		Long freq = conf.getLaserOnlineRetrainingFreqency() * 1000;

		timer.scheduleAtFixedRate(new LaserOfflineTrainTask(), freq, freq);
	}

	public void exit() {
		timer.cancel();
	}

	class LaserOfflineTrainTask extends TimerTask {
		private final Path outputPath;
		private final Integer iterationsMaximum;
		private final Float regularizationFactor;
		private final Boolean addIntercept;
		private final org.apache.hadoop.conf.Configuration conf;

		public LaserOfflineTrainTask() {
			Configuration conf = Configuration.getInstance();
			this.outputPath = conf.getLaserOfflineOutput();
			this.regularizationFactor = conf.getRegularizationFactor();
			this.addIntercept = conf.addIntercept();
			this.iterationsMaximum = conf.getMaxIteration();
			this.conf = new org.apache.hadoop.conf.Configuration();
		}

		@Override
		public void run() {
			try {
				final LaserMetaqThread metaqThread = LaserMetaqThread
						.getInstance();
				long majorVersion = metaqThread.getMajorVersion();

				metaqThread.incrMajorVersion();
				LOG.info(
						"Update MetaQ's output path, major version from {} to {}",
						majorVersion, metaqThread.getMajorVersion());

				Path input = new Path(Configuration.getInstance()
						.getMetaqOutput(), Long.toString(majorVersion) + "-*");
				LOG.info("Retraining Laser's Offline Model, result = {}",
						outputPath);
				
				Path signalData = new Path(outputPath, "ADMM_SIGNAL");
				OfflineFeatureDriver.run(input, signalData, conf);
				
				Path admmOutput = new Path(outputPath, "ADMM");
				AdmmOptimizerDriver.run(signalData, admmOutput,
						regularizationFactor, addIntercept, null,
						iterationsMaximum, conf);
				HadoopUtil.delete(conf, signalData);

				LaserOfflineResultWriter writer = new LaserOfflineResultWriter();
				writer.write(conf, outputPath.getFileSystem(conf), new Path(admmOutput, "ADMM"),
						outputPath);
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
}