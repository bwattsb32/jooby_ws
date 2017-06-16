/**
 * 
 */
package com.dts_inc.z2c.module.rx.sparql;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;
import rx.observables.StringObservable;
import rx.schedulers.Schedulers;
import rx.exceptions.Exceptions;

/**
 * Execute a command line returning an Observable with the command output as a
 * string. Observable should be subscribed to using the io() scheduler.
 * 
 * @author bergstromr
 *
 */
public final class RxProcess {
	static Logger logger = LoggerFactory.getLogger(RxProcess.class);

	public static Observable<String> execute(String... commands) {
		logger.debug("Executing command: [{}]", commands.toString());
		ProcessBuilder builder = new ProcessBuilder(commands);
		builder.redirectErrorStream(true);
		// TODO: Set working directory or environment here?

		Observable<Process> processObs = Observable.create(subscriber -> {
			Process process = null;
			try {
				process = builder.start();
				subscriber.onNext(process);
			} catch (Exception e) {
				logger.error("Error initializing subprocess: ", e);
				subscriber.onError(e);
			}
			subscriber.onCompleted();
		});

		Observable<String> resultObs = processObs.flatMap(process -> {
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			// The assumption here is the observable will close the reader on completion.
			Observable<String> obs = StringObservable.from(reader);
			Observable<String> retval = StringObservable.stringConcat(obs)
					// Log the output using a static function
					.doOnNext(RxProcess::logOutput);
			Observable<String> completion = Observable.create( (Subscriber<? super String> sub) -> {
				try {
					int exitCode = process.waitFor(); // Blocks thread!!
					
					if (exitCode != 0) {
						logger.debug("Process exited code: {}", exitCode);
						sub.onError(new Exception("Process terminated with error code: " + exitCode));
					} else {
						logger.debug("Process completed. Code: {}", exitCode);
						sub.onCompleted();
					}
				} catch (InterruptedException e) {
					sub.onError(e);
				}
			}).subscribeOn(Schedulers.io()); // Note scheduler shift.
			
			return retval.concatWith(completion);
		});

		return resultObs;
	}
	
	// Function to log output
	static private void logOutput(String output) {
		logger.debug("Process Output:\n {}",output);
	}
}
