package com.dts_inc.z2c.z2c_ws;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.jooby.Jooby;
import org.jooby.Results;
import org.jooby.Route.Group;
import org.jooby.Upload;
import org.jooby.handlers.Cors;
import org.jooby.handlers.CorsHandler;
import org.jooby.json.Jackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.exceptions.Exceptions;
import rx.schedulers.Schedulers;

import java.util.Map;

import com.dts_inc.z2c.module.rx.sparql.RxObservables;
import com.dts_inc.z2c.module.rx.sparql.RxProcess;
import com.dts_inc.z2c.module.rx.sparql.RxSparql;
import com.dts_inc.z2c.module.rx.sparql.RxSparqlClient;
import com.dts_inc.z2c.module.sparql.Sparql;
import com.dts_inc.z2c.module.sparql.SparqlClient;
import com.dts_inc.z2c.module.vmware.CreateVM;
import com.dts_inc.z2c.module.vmware.DeleteVM;
import com.dts_inc.z2c.module.vmware.VMUtils;
import com.dts_inc.z2c.z2c_ws.provision.CreateNodeUtil;
import com.dts_inc.z2c.z2c_ws.provision.DeleteNodeUtil;
import com.dts_inc.z2c.z2c_ws.provision.IdGenerator;
import com.dts_inc.z2c.z2c_ws.provision.LoadResource;
import com.dts_inc.z2c.z2c_ws.provision.SemanticHostName;
import com.dts_inc.z2c.z2c_ws.provision.VmwareUtil;
import com.github.jsonldjava.core.JsonLdError;
import com.github.jsonldjava.core.JsonLdOptions;
import com.github.jsonldjava.core.JsonLdProcessor;
import com.github.jsonldjava.utils.JsonUtils;

/**
 * @author jooby generator
 */
@SuppressWarnings("unchecked")
public class App extends Jooby {
	static Logger logger = LoggerFactory.getLogger(App.class);
	static final String JSONLD_CONTEXT="/context.json";
	
	static final String TMP_STORAGE_LOC = "/tmp/cmo/appData/";

	{
		// Exports a JSON renderer and parser as well as an ObjectMapper
		// which you can @inject.
		use(new Jackson());

		// Blazegraph Query Module
		use(new Sparql());
		use(new RxSparql());

		use("*", new CorsHandler(new Cors()));

		//use(new Exec());

		/**
		 * Zero2Cloud Provisioning API
		 */
		use("/api")

		/**
		 * Creates and deploys a virtual node given hostId, environmentId and templateId
		 * @param body
		 *            JSON object containing the scope and id of the entity
		 *            being provisioned.
		 * @return Result response
		 */		
		.post("/deployRx",promise( (req, deferred) -> {
			// Static and loaded values.
			String envQuery = "/scripts/vnode_create.sprql";
			String insertTtl = "/scripts/vnode_insert.sprql";
			String updateTtl = "/scripts/vnode_update.sprql";
			String puppetTemplate = "/tmplt/vm_createpp.tmplt";
			HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
			JsonLdOptions ldOptions = new JsonLdOptions();

			RxSparqlClient client = req.require(RxSparqlClient.class);


			// Queried database properties
			HashMap<String,Object> props = null;

			// Start the chain with the incoming JSON
			Observable<HashMap<String,Object>> propObs = Observable
					.from( ((HashMap<String,Object>)req.body().to(HashMap.class)).entrySet()) // JSON from client
					// ***
					// TODO: Need parameter validation here. Required values, etc.
					// ***
					.reduce( (String) null, (acc, param) -> { // Parameterize query
						if( null == acc) acc = LoadResource.getResourceAsString(envQuery);
						logger.debug("Replacing [{}] with [{}]", param.getKey(), param.getValue());
						acc = ((String)acc).replaceAll("\\%"+param.getKey(), param.getValue().toString());
						return acc;})
					.flatMap(queryString -> client.constructQuery(queryString)) // Query the database
					.map(result -> { // Compact using JSON-LD 
						logger.debug("Query Result: "+ result);
						HashMap<String,Object> compact = null;

						try {
							Object jsonObject = JsonUtils.fromString(result);
							compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, ldOptions);

							compact.remove("@context");
							compact.remove("id");
						} catch (JsonLdError e) {
							logger.error("JsonLdError compacting: ",e);
							throw Exceptions.propagate(e);
						} catch (Exception e) {
							logger.error("Unhandled Error compacting: ",e);
							throw Exceptions.propagate(e);
						}
						return compact;})
					.map(params -> { // Generate the hostname
						String vmHostName = null;
						try {
							vmHostName = new SemanticHostName().generateHostname(params);
							params.put("sys:vmHostName", vmHostName);
							params.put("sys:uuId", IdGenerator.generateUUID());
							//params.put("sys:domainSfx","cmoa3s.com"); // TODO: This should be on the Organization
							params.put("sys:status", "PENDING"); // TODO: Enum?
						} catch (Exception e) {
							logger.error("Error generating hostname: ",e);
							throw Exceptions.propagate(e);
						}
						return params;})
					.flatMap(params -> { // Remember, flatMap does an internal subscribe!

						HashMap<String,Object> retval = new HashMap<String,Object>();
						retval.put("sys:fqdn", params.get("sys:vmHostName")+"."+params.get("sys:domainSfx"));
						retval.put("sys:status", "SUCCESS");
						retval.put("sys:uri", "http://z2c.dts-inc.com/id/"+params.get("sys:uuId"));

						Observable<String> insertObs = Observable.just(params)
								.flatMap(data -> Observable.from(params.entrySet()))
								.reduce( (String) null, (acc, param) -> { // Parameterize insert query
									if( null == acc) acc = LoadResource.getResourceAsString(insertTtl);
									logger.debug("Replacing [{}] with [{}]", param.getKey(), param.getValue());
									acc = ((String)acc).replaceAll("\\%"+param.getKey(), param.getValue().toString());
									logger.debug("Turtle: {}", acc);
									return acc;})
								.flatMap(ttlString -> client.turtleInsert(ttlString));

						// Insert the config entry in to the database
						return insertObs 
								// Run the process which depends on the data
								.flatMap(data ->{ 
									CreateVM.createVM(params);
									return Observable.just(retval);
									//return RxProcess.execute("/usr/local/bin/puppet", "apply", builder.toString());
								})
								.flatMap(data ->{ 
									CreateVM.configureVM(params);
									return Observable.just(retval);
								})
								.flatMap(data ->{ 
									String ipaddress = "";
									ipaddress = VMUtils.getIpAddress(params);
									params.put("tag:ipAddress", ipaddress);
									
									Observable<String> updateObs = Observable.just(params)
											.flatMap(data1 -> Observable.from(params.entrySet()))
											.reduce( (String) null, (acc, param) -> { // Parameterize insert query
												if( null == acc) acc = LoadResource.getResourceAsString(updateTtl);
												logger.debug("Replacing [{}] with [{}]", param.getKey(), param.getValue());
												acc = ((String)acc).replaceAll("\\%"+param.getKey(), param.getValue().toString());
												logger.debug("Turtle: {}", acc);
												return acc;})
											.flatMap(ttlString -> client.turtleInsert(ttlString));

									// Insert the config entry in to the database
									return updateObs 
											// Normal indication back to the client.
											.flatMap(ignored -> Observable.just(retval)); 
								})
								// Normal indication back to the client.
								.flatMap(ignored -> Observable.just(retval)); 
					})
					// Use onErrorReturn to transform error in to JSON for client
					.onErrorResumeNext(t -> {
						logger.error("Deployment pipeline error: ", t );
						HashMap<String,Object> retval =  new HashMap<String,Object>();
						retval.put("sys:status", "error");
						retval.put("sys:message", t.getMessage());
						return Observable.just(retval);
					});

			propObs.subscribeOn(Schedulers.io())
			.subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint that retrieves all templates for a given system
		 * @param body
		 *            JSON object containing the scope and id of the entity
		 *            being provisioned.
		 * @return Result response
		 */		
		.post("/templates",promise( (req, deferred) -> {


			//String sparqlQuery = new String(Files.readAllBytes(Paths.get("conf/scripts/get_templates.sprql")));
			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_templates.sprql");


			HashMap<String,Object> body = (HashMap<String, Object>) req.body().to(Object.class);
			logger.debug("Incoming request: {}", body);
			logger.debug("OrgId: {}", "<"+body.get("orgId")+">");

			String finalQuery = sparqlQuery.replaceAll("\\?org","<"+body.get("orgId")+">");
			logger.debug("Final query: {}", finalQuery);
			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
					compact.remove("id");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all Virtual Nodes for a given system
		 * @param body
		 *            JSON object containing URI of the organization "{orgId":"xxx"}.
		 * @return Result response
		 */		
		.post("/virtualnodes",promise( (req, deferred) -> {


			//String sparqlQuery = new String(Files.readAllBytes(Paths.get("conf/scripts/get_virtualnodes.sprql")));
			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_virtualnodes.sprql");

			HashMap<String,Object> body = (HashMap<String, Object>) req.body().to(Object.class);
			logger.debug("Incoming request: {}", body);
			logger.debug("OrgId: {}", "<"+body.get("orgId")+">");
			String finalQuery = sparqlQuery.replaceAll("\\?org","<"+body.get("orgId")+">");
			logger.debug("Final query: {}", finalQuery);

			SparqlClient client = req.require(SparqlClient.class);


			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
					compact.remove("id");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Enpoint that retrieves all Virtual Hosts
		 * @param body
		 *            JSON object containing URI of the organization "{orgId":"xxx"}.
		 * @return Result response
		 */		
		.get("/virtualhosts",promise( (req, deferred) -> {


			//String sparqlQuery = new String(Files.readAllBytes(Paths.get("conf/scripts/get_virtualhosts.sprql")));
			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_virtualhosts.sprql");


			// No parameters
			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
					compact.remove("id");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint to retrieve all environments given organization ID
		 * @param orgId
		 *            UUID of the organization
		 * @return Result response
		 */		
		.get("/environments/:orgId",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_environments.sprql");

			// Get the parameter(s)
			String orgId = "<http://z2c.dts-inc.com/id/"+req.param("orgId").value()+">";
			logger.debug("OrgId: {}", orgId);
			String finalQuery = sparqlQuery.replaceAll("\\?org",":"+orgId);
			logger.debug("Final query: {}", finalQuery);

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all networks given environment ID
		 * @param orgId
		 *            UUID of the environment
		 * @return Result response
		 */		
		.get("/networks/:environmentId",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_networks.sprql");

			// Get the parameter(s)
			String environmentId = "<http://z2c.dts-inc.com/id/"+req.param("environmentId").value()+">";
			logger.debug("EnvironmentId: {}", environmentId);
			String finalQuery = sparqlQuery.replaceAll("\\?env",":"+environmentId);
			logger.debug("Final query: {}", finalQuery);

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))



		/**
		 * Endpoint that retrieves all Virtual Nodes for a given system
		 * @param body
		 *            JSON object containing URI of the organization "{orgId":"xxx"}.
		 * @return Result response
		 */		
		.post("/virtualNodes/getAll",promise( (req, deferred) -> {


			//String sparqlQuery = new String(Files.readAllBytes(Paths.get("conf/scripts/get_virtualnodes.sprql")));
			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_virtualnodes.sprql");

			HashMap<String,Object> body = (HashMap<String, Object>) req.body().to(Object.class);
			logger.debug("Incoming request: {}", body);
			logger.debug("OrgId: {}", "<"+body.get("orgId")+">");
			String finalQuery = sparqlQuery.replaceAll("\\?org","<"+body.get("orgId")+">");
			logger.debug("Final query: {}", finalQuery);

			SparqlClient client = req.require(SparqlClient.class);


			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
					compact.remove("id");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint to delete a VM given virtual node ID
		 * @param nodeId
		 *            URI of the virtual node
		 * @return Result response
		 */		
		.post("/virtualNodes/deleteRx",promise( (req, deferred) -> {
			final String VM_ID_KEY = "id";
			final String sparqlQuery = "/scripts/vnode_delete.sprql";
			final String deleteScript = "/tmplt/vm_deletepp.tmplt";

			// Context for compaction
			HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream(JSONLD_CONTEXT));
			JsonLdOptions ldOptions = new JsonLdOptions();

			// Get the object to make database requests.
			RxSparqlClient client = req.require(RxSparqlClient.class);

			// Use the input JSON as the top level observable
			Observable<HashMap<String, Object>> rspObs = Observable.just(((HashMap<String,Object>)req.body().to(HashMap.class)))

					// Validate Input. Throw Runtime validation exception
					.flatMap( (HashMap<String,Object> data) -> {
						// Validate parameters. For now, just check that we got one.

						if( data.containsKey(VM_ID_KEY)) {
							return Observable.just(data);
						} else {
							return Observable.error(new Exception("Missing required parameter: ["+VM_ID_KEY+"]"));
						}
					})

					// Parameterize the info query
					.flatMap( data -> {return Observable.from( ((HashMap<String,Object>)data).entrySet());})
					.reduce( (String) null, (acc, param) -> { // Parameterize query
						if( null == acc) acc = LoadResource.getResourceAsString(sparqlQuery);
						logger.debug("Replacing [{}] with [{}]", param.getKey(), param.getValue());
						acc = ((String)acc).replaceAll("\\?"+param.getKey(), param.getValue().toString());
						return acc;})

					// Run the query
					.flatMap(queryString -> client.constructQuery(queryString)) // Query the database

					// Compact the result
					.map(result -> { // Compact using JSON-LD 
						logger.debug("Query Result: "+ result);
						HashMap<String,Object> compact = null;

						try {
							Object jsonObject = JsonUtils.fromString(result);
							compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, ldOptions);

							compact.remove("@context");
							// Need the id for later.
							//compact.remove("id");
						} catch (JsonLdError e) {
							logger.error("JsonLdError compacting: ",e);
							throw Exceptions.propagate(e);
						} catch (Exception e) {
							logger.error("Unhandled Error compacting: ",e);
							throw Exceptions.propagate(e);
						}
						return compact;})
					// Verify write protect
					.flatMap( (HashMap<String,Object> data) -> {
						if( data.containsKey("sys:deleteProtect")) {
							if( data.get("sys:deleteProtect").toString().equalsIgnoreCase("true")) { 
								return Observable.error(new Exception("Acess denied. VM delete protected."));
							} else {
								return Observable.just(data);
							}
						} else {
							return Observable.error(new Exception("Access denied. By default, VM is delete protected."));
						}
					})
					// Write the script to disk
					.flatMap(data -> {
						// Build our file name
						StringBuilder builder = new StringBuilder("/tmp/delete-")
								.append( ((HashMap<String,Object >) data).get("sys:hostName").toString().toLowerCase())
								.append(".pp");

						// Write the script to the disk
						try {
							BufferedWriter bfw = new BufferedWriter(new FileWriter(new File(builder.toString())));

							// Parameterize the delete script
							RxObservables.parameterizeScript(deleteScript, ":", (HashMap<String,Object>)data)
							.doOnNext(script -> logger.debug("Delete Script:\n {}", script))
							.doAfterTerminate(() -> {
								try {
									logger.debug("Script writer closed.");
									bfw.close();
								} catch (Exception e) {
									logger.error("Error writting script file: ",e);
									Exceptions.propagate(e);
								}
							})
							.subscribe(script -> {
								try {
									bfw.write(script);
								} catch (Exception e) {
									logger.error("Error writting script file: ",e);
									Exceptions.propagate(e);
								}
							});
						} catch (Exception e) {
							logger.error("Error writting script file: ",e);
							Exceptions.propagate(e);
						}

						// Pass the parameters downstream.
						return Observable.just(data);
					})

					// execute script.
					.flatMap(params -> {

						logger.debug("params is " + params.toString());

						//execute guest command to unregister OS
						DeleteVM.configureVM(params);

						DeleteVM.pause (5, "executing command on vm before delete");

						//execute VMWare SDK delete
						DeleteVM.deleteVM(params);

						// Build our file name
						StringBuilder builder = new StringBuilder("/tmp/delete-")
								.append(((HashMap<String,Object>) params).get("sys:hostName").toString().toLowerCase())
								.append(".pp");
						
//						StringBuilder builderDel = new StringBuilder("describe <")
//								.append(((HashMap<String,Object>) params).get("id"))
//								.append(">");
//						logger.debug("Delete Query: {}", builderDel.toString());
//						client.deleteWithQuery(builderDel.toString());

						return RxProcess
							.execute("/usr/local/bin/puppet", "apply", builder.toString())
							.flatMap(ignored -> { return Observable.just(params);}); // Flow the parameters downstream
						
						//return Observable.just(params);
						
					})
					// Delete the virtual node
					.flatMap(data -> {
						StringBuilder builder = new StringBuilder("describe <")
								.append(((HashMap<String,Object>) data).get("id"))
								.append(">");
						logger.debug("Delete Query: {}", builder.toString());
						return client.deleteWithQuery(builder.toString());
						//return Observable.just(data);
					})
					// Return value for client.
					.flatMap(ignored -> {
						HashMap<String,Object> retval = new HashMap<String,Object>();
						retval.put("sys:status", "SUCESS");
						return Observable.just(retval);
					})
					// Use onErrorReturn to transform error in to JSON for client
					.onErrorResumeNext(t -> {
						logger.error("Pipeline error: ", t );
						HashMap<String,Object> retval =  new HashMap<String,Object>();
						retval.put("sys:status", "error");
						retval.put("sys:message", t.getMessage());
						return Observable.just(retval);
					});		


			// Subscribe using the deferred Lambdas.			
			rspObs.subscribeOn(Schedulers.io()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint to delete a VM given virtual node ID from DB only
		 * @param nodeId
		 *            URI of the virtual node
		 * @return Result response
		 */		
		.post("/virtualNodes/deleteDBRx",promise( (req, deferred) -> {
			final String VM_ID_KEY = "id";
			final String sparqlQuery = "/scripts/vnode_delete.sprql";
			final String deleteScript = "/tmplt/vm_deletepp.tmplt";

			// Context for compaction
			HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream(JSONLD_CONTEXT));
			JsonLdOptions ldOptions = new JsonLdOptions();

			// Get the object to make database requests.
			RxSparqlClient client = req.require(RxSparqlClient.class);

			// Use the input JSON as the top level observable
			Observable<HashMap<String, Object>> rspObs = Observable.just(((HashMap<String,Object>)req.body().to(HashMap.class)))

					// Validate Input. Throw Runtime validation exception
					.flatMap( (HashMap<String,Object> data) -> {
						// Validate parameters. For now, just check that we got one.

						if( data.containsKey(VM_ID_KEY)) {
							return Observable.just(data);
						} else {
							return Observable.error(new Exception("Missing required parameter: ["+VM_ID_KEY+"]"));
						}
					})

					// Parameterize the info query
					.flatMap( data -> {return Observable.from( ((HashMap<String,Object>)data).entrySet());})
					.reduce( (String) null, (acc, param) -> { // Parameterize query
						if( null == acc) acc = LoadResource.getResourceAsString(sparqlQuery);
						logger.debug("Replacing [{}] with [{}]", param.getKey(), param.getValue());
						acc = ((String)acc).replaceAll("\\?"+param.getKey(), param.getValue().toString());
						return acc;})

					// Run the query
					.flatMap(queryString -> client.constructQuery(queryString)) // Query the database

					// Compact the result
					.map(result -> { // Compact using JSON-LD 
						logger.debug("Query Result: "+ result);
						HashMap<String,Object> compact = null;

						try {
							Object jsonObject = JsonUtils.fromString(result);
							compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, ldOptions);

							compact.remove("@context");
							// Need the id for later.
							//compact.remove("id");
						} catch (JsonLdError e) {
							logger.error("JsonLdError compacting: ",e);
							throw Exceptions.propagate(e);
						} catch (Exception e) {
							logger.error("Unhandled Error compacting: ",e);
							throw Exceptions.propagate(e);
						}
						return compact;})
					// Verify write protect
					.flatMap( (HashMap<String,Object> data) -> {
						if( data.containsKey("sys:deleteProtect")) {
							if( data.get("sys:deleteProtect").toString().equalsIgnoreCase("true")) { 
								return Observable.error(new Exception("Acess denied. VM delete protected."));
							} else {
								return Observable.just(data);
							}
						} else {
							return Observable.error(new Exception("Access denied. By default, VM is delete protected."));
						}
					})

					// Delete the virtual node
					.flatMap(data -> {
						StringBuilder builder = new StringBuilder("describe <")
								.append(((HashMap<String,Object>) data).get("id"))
								.append(">");
						logger.debug("Delete Query: {}", builder.toString());
						return client.deleteWithQuery(builder.toString());
					})
					// Return value for client.
					.flatMap(ignored -> {
						HashMap<String,Object> retval = new HashMap<String,Object>();
						retval.put("sys:status", "SUCESS");
						return Observable.just(retval);
					})
					// Use onErrorReturn to transform error in to JSON for client
					.onErrorResumeNext(t -> {
						logger.error("Pipeline error: ", t );
						HashMap<String,Object> retval =  new HashMap<String,Object>();
						retval.put("sys:status", "error");
						retval.put("sys:message", t.getMessage());
						return Observable.just(retval);
					});		


			// Subscribe using the deferred Lambdas.			
			rspObs.subscribeOn(Schedulers.io()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint to delete a VM given virtual node ID
		 * @param nodeId
		 *            URI of the virtual node
		 * @return Result response
		 */		
		.post("/virtualNodes/delete",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);


			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: ", body);

			String nodeId =  "<" +(String) body.get("id") + ">";

			//String nodeId = "<http://z2c.dts-inc.com/id/"+req.param("nodeId").value()+">";


			//This query retrieves info from Blazegraph so that the delete script can be generated
			//and executed.
			String sparqlQuery = DeleteNodeUtil.getDeleteQuery(nodeId);
			logger.debug("sparqlQuery query: {}", sparqlQuery);

			//This is the blazegraph query that deletes node info from blazegraph
			String deleteQuery = "describe ?nodeId";

			String finalQuery = deleteQuery.replaceAll("\\?nodeId", nodeId);
			logger.debug("Final query: {}", finalQuery);


			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {

				try {	
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
					boolean deleteProtected = false;

					if (sparqlQuery.contains("sys:deleteProtect \"true\"")){
						deleteProtected=true;
					}
					if (deleteProtected==false){
						subscriber.onStart();
						client.executeDelete(finalQuery, subscriber);
						subscriber.onCompleted();
					}
				} catch (Exception e) {
					subscriber.onError(e);
				}		


			}).map(result -> {	
				logger.debug("-->>> Result: "+ result);
				HashMap<String,Object> compact = null;

				String vmHostName = null;
				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){

					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();

						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);

						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("JsonLdError compacting: ",e);
					} catch (Exception e) {
						logger.error("Unhandled Error compacting: ",e);
					}

					vmHostName = compact.get("sys:hostName").toString().toUpperCase();

					//Generate the puppet script
					DeleteNodeUtil.generateScript(compact, nodeId);
					logger.debug("....generating delete script for " + nodeId);

					if (vmHostName != null){
						//Execute the script
						DeleteNodeUtil.executeScript(vmHostName);	
						logger.debug("....executing delete script for " + vmHostName);
					}

				}



				return compact;


			})
			.subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Description here
		 * @param orgId
		 *            UUID of the organization
		 * @return Result response
		 */		
		.post("/notify/:nodeId",promise( (req, deferred) -> {


			SparqlClient client = req.require(SparqlClient.class);

			String nodeId = "<http://z2c.dts-inc.com/id/"+req.param("nodeId").value()+">";

			String sparqlQuery = "";
			String finalSparqlQuery = sparqlQuery.replaceAll("\\?nodeId","<"+nodeId+">");
			logger.debug("Final sqarql query: {}", finalSparqlQuery);

			String insertQuery = "";
			String finalInsertQuery = insertQuery.replaceAll("\\?nodeId","<"+nodeId+">");
			logger.debug("Final insert query: {}", finalInsertQuery);

			String deleteQuery = "";
			String finalDeleteQuery = deleteQuery.replaceAll("\\?nodeId","<"+nodeId+">");
			logger.debug("Final delete query: {}", finalDeleteQuery);

			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {	
					client.executeDelete(finalDeleteQuery, subscriber);
					subscriber.onCompleted();

					subscriber.onStart();
					client.executeInsert(finalInsertQuery, subscriber);
					subscriber.onCompleted();

					subscriber.onStart();
					client.executeQuery(finalSparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = null;

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}

				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint to create TemplateNode
		 * @param 
		 * @return Result response
		 */		
		.post("/templates/create",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);

			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: ", body);

			String uuid = IdGenerator.generateUUID();
			String timeStamp = IdGenerator.generateTimeStamp();
			String prefLabel = (String) body.get("prefLabel");
			String altLabel = (String) body.get("altLabel");
			String cpus = (String) body.get("cpus");
			String memory = (String) body.get("memory");
			ArrayList<String> requires = (ArrayList<String>) body.get("requires");

			//load template node insert from file
			String insertQuery = LoadResource.getResourceAsString("/scripts/templateNode_insert.sprql");
			insertQuery = insertQuery.replaceAll("\\?uuid", uuid);
			insertQuery = insertQuery.replaceAll("\\?timeStamp", timeStamp);
			insertQuery = insertQuery.replaceAll("\\?prefLabel", prefLabel);
			insertQuery = insertQuery.replaceAll("\\?altLabel", altLabel);
			insertQuery = insertQuery.replaceAll("\\?cpus", cpus);
			insertQuery = insertQuery.replaceAll("\\?memory", memory);

			//add components script from file
			String componentQueryPart = LoadResource.getResourceAsString("/scripts/templateNode_componentInsert.sprql");

			componentQueryPart = componentQueryPart.replaceAll("\\?uuid", uuid);
			ArrayList<String> componentSection = new ArrayList<String>();

			//iterate through the requires map to build components section.
			for (String temp: requires){
				String component = "<" + temp + ">.\n";
				componentSection.add(componentQueryPart.concat(component));						
			}

			//build the component section to add to the insert script
			String finalCompSection = "";

			for (String temp2: componentSection){
				finalCompSection = finalCompSection + temp2;
				logger.debug("temp2..." + temp2);
			}

			String finalInsertQuery = insertQuery + "\n" +finalCompSection;
			logger.debug(finalInsertQuery);

			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {	
					client.executeInsert(finalInsertQuery.toString(), subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = null;

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}

				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))


		/**
		 * Endpoint that retrieves all templates for a given system
		 * @param body
		 *            JSON object containing the scope and id of the entity
		 *            being provisioned.
		 * @return Result response
		 */		
		.post("/templates/getAll",promise( (req, deferred) -> {


			//String sparqlQuery = new String(Files.readAllBytes(Paths.get("conf/scripts/get_templates.sprql")));
			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_templates.sprql");


			HashMap<String,Object> body = (HashMap<String, Object>) req.body().to(Object.class);
			logger.debug("Incoming request: {}", body);
			logger.debug("OrgId: {}", "<"+body.get("orgId")+">");

			String finalQuery = sparqlQuery.replaceAll("\\?org","<"+body.get("orgId")+">");
			logger.debug("Final query: {}", finalQuery);
			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
					compact.remove("id");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))


		/**
		 * Endpoint to update a Template
		 * @param 
		 * @return Result response
		 */		
		.post("/templates/update",promise( (req, deferred) -> {


			SparqlClient client = req.require(SparqlClient.class);

			String nodeId = "<http://z2c.dts-inc.com/id/"+req.param("uuid").value()+">";

			//This is the blazegraph query that deletes node info from blazegraph
			String deleteQuery = "describe ?nodeId";

			String finalDeleteQuery = deleteQuery.replaceAll("\\?nodeId", nodeId);

			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: ", body);

			String uuid = (String) body.get("uuid");
			String timeStamp = (String) body.get("timeStamp");
			String prefLabel = (String) body.get("prefLabel");
			String altLabel = (String) body.get("altLabel");
			String cpus = (String) body.get("cpus");
			String memory = (String) body.get("memory");
			ArrayList<String> requires = (ArrayList<String>) body.get("requires");

			//load template node insert from file
			String insertQuery = LoadResource.getResourceAsString("/scripts/templateNode_insert.sprql");
			insertQuery = insertQuery.replaceAll("\\?uuid", uuid);
			insertQuery = insertQuery.replaceAll("\\?timeStamp", timeStamp);
			insertQuery = insertQuery.replaceAll("\\?prefLabel", prefLabel);
			insertQuery = insertQuery.replaceAll("\\?altLabel", altLabel);
			insertQuery = insertQuery.replaceAll("\\?cpus", cpus);
			insertQuery = insertQuery.replaceAll("\\?memory", memory);

			//add components script from file
			String componentQueryPart = LoadResource.getResourceAsString("/scripts/templateNode_componentInsert.sprql");

			componentQueryPart = componentQueryPart.replaceAll("\\?uuid", uuid);
			ArrayList<String> componentSection = new ArrayList<String>();

			//iterate through the requires map to build components section.
			for (String temp: requires){
				String component = "<" + temp + ">.\n";
				componentSection.add(componentQueryPart.concat(component));						
			}

			//build the component section to add to the insert script
			String finalCompSection = "";

			for (String temp2: componentSection){
				finalCompSection = finalCompSection + temp2;
				logger.debug("temp2..." + temp2);
			}

			String finalInsertQuery = insertQuery + "\n" +finalCompSection;
			logger.debug(finalInsertQuery);

			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {	
					client.executeDelete(finalDeleteQuery, subscriber);
					subscriber.onCompleted();

					subscriber.onStart();
					client.executeInsert(finalInsertQuery.toString(), subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = null;

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}

				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))


		/**
		 * Endpoint to delete a template given template ID
		 * @param orgId
		 *            UUID of the template
		 * @return Result response
		 */		
		.get("/templates/delete/:nodeId",promise( (req, deferred) -> {


			SparqlClient client = req.require(SparqlClient.class);

			String nodeId = "<http://z2c.dts-inc.com/id/"+req.param("nodeId").value()+">";

			//This is the blazegraph query that deletes node info from blazegraph
			String deleteQuery = "describe ?nodeId";

			String finalQuery = deleteQuery.replaceAll("\\?nodeId", nodeId);
			logger.debug("Final query: {}", finalQuery);

			Observable.<String> create(subscriber -> {
				try {	
					client.executeDelete(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {	
				logger.debug("-->>> Result: "+ result);
				HashMap<String,Object> compact = null;

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){

					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();

						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);

						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("JsonLdError compacting: ",e);
					} catch (Exception e) {
						logger.error("Unhandled Error compacting: ",e);
					}

				}

				return compact;
			})
			.subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))



		/**
		 * Endpoint to create Component
		 * @param 
		 * @return Result response
		 */		
		.post("/components/create",promise( (req, deferred) -> {


			SparqlClient client = req.require(SparqlClient.class);

			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: ", body);

			String uuid = IdGenerator.generateUUID();
			String componentType = (String) body.get("componentType");
			String prefLabel = (String) body.get("prefLabel");
			String versionNumber = (String) body.get("versionNumber");
			String altLabel = (String) body.get("altLabel");
			String packageName = (String) body.get("packageName");
			String puppetModule = (String) body.get("puppetModule");
			String puppetClass = (String) body.get("puppetClass");
			String componentId = (String) body.get("componentId");


			String insertQuery = LoadResource.getResourceAsString("/scripts/component_insert.sprql");
			insertQuery = insertQuery.replaceAll("\\?uuid", uuid);
			insertQuery = insertQuery.replaceAll("\\?componentType", componentType);
			insertQuery = insertQuery.replaceAll("\\?prefLabel", prefLabel);
			insertQuery = insertQuery.replaceAll("\\?versionNumber", versionNumber);
			insertQuery = insertQuery.replaceAll("\\?altLabel", altLabel);
			insertQuery = insertQuery.replaceAll("\\?packageName", packageName);
			insertQuery = insertQuery.replaceAll("\\?puppetModule", puppetModule);
			insertQuery = insertQuery.replaceAll("\\?puppetClass", puppetClass);
			insertQuery = insertQuery.replaceAll("\\?componentId", componentId);



			String finalInsertQuery = insertQuery;
			logger.debug(insertQuery);
			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {	
					client.executeInsert(finalInsertQuery, subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = null;

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}

				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint that retrieves all components in the DB
		 * @return Result response
		 */		
		.get("/components/getAll",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_components.sprql");



			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all components in the DB
		 * @return Result response
		 */		
		.get("/components/getEnvironments",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_components_by_environment.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))


		/**
		 * Endpoint that retrieves all components in the DB
		 * @return Result response
		 */		
		.get("/components/getNetworks",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_components_by_network.sprql");



			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))


		/**
		 * Endpoint that retrieves all components in the DB
		 * @return Result response
		 */		
		.get("/components/getOS",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_components_by_os.sprql");



			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;

				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context4select.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}

				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all components in the DB
		 * @return Result response
		 */		
		.get("/components/getSoftPkgs",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_components_by_soft_pkg.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context4select.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))


		/**
		 * Endpoint that retrieves all components in the DB
		 * @return Result response
		 */		
		.get("/components/getPhysicalNodes",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_components_by_physical_node.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all components in the DB
		 * @return Result response
		 */		
		.get("/components/getTemplateNodes",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_components_by_template_node.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all components in the DB
		 * @return Result response
		 */		
		.get("/components/getVirtualNodes",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_components_by_virtual_node.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all inventory types (all/hardware/software/virtualware)
		 * @return Result response
		 */		
		.get("/inventory/getInventory",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);

			// Get the parameter(s)
			String inventoryType = req.param("invType").value();
//			logger.debug("1-invType:" + inventoryType + "/");
			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_inventory.sprql");
			String replaceString ="";
			if (inventoryType.equals("all")){
				replaceString = "FILTER (?type = sys:Device || (?type = sys:Component && bound(?isVersionOf)))";
			}
			if (inventoryType.equals("hardware")){
				replaceString = "FILTER (?type = sys:Device)\n MINUS {?s rdf:type sys:VirtualNode.}";
			}
			if (inventoryType.equals("software")){
				replaceString = "FILTER (?type = sys:Component && bound(?isVersionOf))";
			}
			if (inventoryType.equals("virtualware")){
				replaceString = "FILTER (?type = sys:VirtualNode)";
			}

			String finalQuery = sparqlQuery.replaceAll("\\#replacestring", replaceString);
			logger.debug("2-finalquery:" + finalQuery + "/");
			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all configuration types (all/hardware/software(parent))
		 * @return Result response
		 */		
		.get("/config/getInventory",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);

			// Get the parameter(s)
			String inventoryType = req.param("invType").value();
//			logger.debug("1-invType:" + inventoryType + "/");
			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_inventory.sprql");
			String replaceString ="";
			if (inventoryType.equals("all")){
				replaceString = "FILTER (?type = sys:Device || (?type = sys:Component && !bound(?isVersionOf)))\n MINUS {?s rdf:type sys:VirtualNode.}";
			}
			if (inventoryType.equals("hardware")){
				replaceString = "FILTER (?type = sys:Device)\n MINUS {?s rdf:type sys:VirtualNode.}";
			}
			if (inventoryType.equals("software")){
				replaceString = "FILTER (?type = sys:Component && !bound(?isVersionOf))";
			}
//			if (inventoryType.equals("virtualware")){
//				replaceString = "FILTER (?type = sys:VirtualNode)";
//			}

			String finalQuery = sparqlQuery.replaceAll("\\#replacestring", replaceString);
			logger.debug("2-finalquery:" + finalQuery + "/");
			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves all hardware devices (physical server, UPS, Network and Storage)
		 * @return Result response
		 */		
		.get("/inventory/getHardwareList",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_device.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves a specific node - intended for an inventory node
		 * @return Result response
		 */		
//			.get("/config/getDeviceNode/:nodeId",promise( (req, deferred) -> {
		.get("/config/getDeviceNode",promise( (req, deferred) -> {
// (   "sys:endOfLife": {
//		     "@type":"xsd:dateTime"
//		   },)
//			http://localhost:9090/api/config/getDeviceNode?nodeId=113c0d94-d473-4bb0-ad0a-9f2cffb8f40e
//			http://localhost:9090/api/config/getDeviceNode?nodeId=3e03cfbf-1b06-4e60-8381-3fc14925a156
//		    http://localhost:9090/api/config/getDeviceNode?nodeId=129699f4-c1b3-457e-aacf-c5d5060f1144/7.0.69
				String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_device.sprql");
				// Get the parameter(s)
				String nodeId = req.param("nodeId").value();
				logger.debug("NodeId:" + nodeId);
				String finalQuery = sparqlQuery.replaceAll("\\?s ","<http://z2c.dts-inc.com/id/"+nodeId+"> ");
//				logger.debug("Final query:"+ finalQuery);

				SparqlClient client = req.require(SparqlClient.class);

				Observable.<String> create(subscriber -> {
					try {
						client.executeQuery(finalQuery, subscriber);
						subscriber.onCompleted();
					} catch (Exception e) {
						subscriber.onError(e);
					}
				}).map(result -> {
					HashMap<String,Object> compact = null;
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
					return compact;
				}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);


		}))

		/**
		 * Endpoint that retrieves  the list of templates 
		 * @return Result response
		 */		
		.get("/inventory/getTemplateNodeList",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_template_nodes_list.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves  the list of racks 
		 * @return Result response
		 */		
		.get("/config/getRackList",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_rack_nodes_list.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves  the list of clusters  
		 * @return Result response
		 */		
		.get("/config/getClusterList",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_cluster_nodes_list.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves  the list of hardware device types  
		 * @return Result response
		 */		
		.get("/config/getHardwareDeviceTypeList",promise( (req, deferred) -> {


			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_hardware_device_types_list.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(sparqlQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))

		/**
		 * Endpoint that retrieves  the list of hardware device types  
		 * @return Result response
		 */		
		.get("/config/getFieldsListByDeviceLink",promise( (req, deferred) -> { 

			String listItem = req.param("majorMinorTypes").value();
//			String majorType = listItem.substring(0,2);
//			String minorType = listItem.substring(3,5);
//			String minorMinorType = "";
//			if (majorType.equals("SW")){
//				minorMinorType = listItem.substring(6,8);
//			}
//			logger.debug("1-LI/Maj/Min:" + listItem);
//			logger.debug("1-LI/Maj/Min:" + majorType);
//			logger.debug("1-LI/Maj/Min:" + minorType);
			String sparqlQuery = "";
			sparqlQuery = LoadResource.getResourceAsString("/scripts/get_fields_list_by_device_type.sprql");
			
			switch (listItem){
				case "HW-PS": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/d73a29a2-9b06-471e-95e5-01351647a0c4>)";break;
				case "HW-NT": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/ff8e302e-ce7f-4360-b37a-c7c6b9e3a127>)";break;
				case "HW-UP": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/187538c9-0593-4b7f-bcce-d9028c8d95b6>)";break;
				case "SW-OS-PA": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/209f7737-6298-4679-910b-fcbc03d9f904>)";break;
				case "SW-OS-CH": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/d8914052-2840-4453-8218-ec7e3753e630>)";break;
				case "SW-SC-PA": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/866d1a68-5738-4fd8-8f62-bc6a5efd2c92>)";break;
				case "SW-SC-CH": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/515e7495-2a55-4cf9-a685-781ac76a4815>)";break;
				case "SW-DS-PA": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/8d966aa0-2225-4e5d-b0c1-6f144e7a7f8e>)";break;
				case "SW-DS-CH": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/e983aaff-ce9a-45c8-b4e6-84e4b275d4ca>)";break;
				case "SW-SP-PA": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/cd0c4c49-ffa0-4744-a2c6-e4cc71f961f4>)";break;
				case "SW-SP-CH": sparqlQuery += "FILTER (?ListItem = <http://z2c.dts-inc.com/id/2c0e0234-894e-457c-ac68-614bf01ea20f>)";break;
			}
			sparqlQuery += "}";
			
			String finalQuery = sparqlQuery;
//			logger.debug("1-finalQuery:" + finalQuery);

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

		}))
		/**
		 * Endpoint that retrieves  the list of software types  (used in add software)
		 * @return Result response
		 */		
		.get("/config/getSoftwareComponentTypeList",promise( (req, deferred) -> { 

//			String deviceType = req.param("deviceType").value();
			String sparqlQuery = LoadResource.getResourceAsString("/scripts/get_software_component_types_list.sprql");
			String finalQuery = sparqlQuery;
//			logger.debug("1-finalQuery:" + finalQuery);

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);

	}))
		
		/**
		 * Endpoint to add a hardware (UPS/Physical Node{Physical Server}/Network Device/Storage Appliance) (5 fields only)
		 * @param 
		 * @return Result response
		 */		
		.post("/inventory/addHardware",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);

			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: ", body);

			String uuid = IdGenerator.generateUUID();
			String manufacturer = (String) body.get("tag:manufacturer");
			String modelName = (String) body.get("tag:modelName");
			String description = (String) body.get("sys:description");
			String deviceType = (String) body.get("sys:deviceType");//Physical server/UPS/ Network device/Storage appliance
			String altLabel = (String) body.get("skos:altLabel");
			String prefLabel = (String) body.get("skos:prefLabel");

			//load template node insert from file
			String IQ1 = LoadResource.getResourceAsString("/scripts/just_prefixes.sprql");
			IQ1 += "<http://z2c.dts-inc.com/id/" + uuid + "> ";
			if(manufacturer != null && !manufacturer.isEmpty()){
			    IQ1 += "tag:manufacturer \"" + manufacturer + "\";\n";
		    }
			if(modelName != null && !modelName.isEmpty()){
				IQ1 += "tag:modelName \"" + modelName + "\";\n";
			}
			if(description != null && !description.isEmpty()){
				IQ1 += "sys:description \"" + description + "\";\n";
			}
			if(deviceType != null && !deviceType.isEmpty()){
				IQ1 += "sys:deviceType \"" + deviceType + "\";\n";
			}
			if(altLabel != null && !altLabel.isEmpty()){
				IQ1 += "skos:altLabel \"" + altLabel + "\";\n";
			}
			if(prefLabel != null && !prefLabel.isEmpty()){
				IQ1 += "skos:prefLabel \"" + prefLabel +  " " + uuid.substring(28,36) + "\";\n";
			}
			IQ1 += "sys:status \"added\";\n";//Initially inserted nodes have status=inactive
			IQ1 += "sys:deleteProtect \"false\";\n";//Initially inserted nodes have delete protect=false
//			Build rdf string which varies with the deviceType
//			a sys:Device;          #for all deviceType - Physical server/UPS/Network device/Storage appliance
//			a sys:Node;            #for Physical server only
//			a sys:PhysicalNode;    #for Physical server only
//			a sys:NetworkDevice;   #for Network device only
//			a sys:UPS;             #for UPS only
//			a sys:StorageAppliance;#for Storage appliance only
			if (deviceType.equals("Physical server")){
				IQ1 += "a sys:Node;\n"+"a sys:PhysicalNode;\n"; 
				IQ1 += "sys:hasDataTemplate <http://z2c.dts-inc.com/id/d73a29a2-9b06-471e-95e5-01351647a0c4>;\n"; 
			}
			if (deviceType.equals("Network device")){
				IQ1 += "a sys:NetworkDevice;\n";
				IQ1 += "sys:hasDataTemplate <http://z2c.dts-inc.com/id/ff8e302e-ce7f-4360-b37a-c7c6b9e3a127>;\n"; 
			}
			if (deviceType.equals("UPS")){
				IQ1 += "a sys:UPS;\n";
				IQ1 += "sys:hasDataTemplate <http://z2c.dts-inc.com/id/187538c9-0593-4b7f-bcce-d9028c8d95b6>;\n"; 
			}
			if (deviceType.equals("Storage appliance")){
				IQ1 += "sys:StorageAppliance;\n";
			}
			IQ1 += "a sys:Device.\n";
			String finalInsertQuery = IQ1; //+ "\n" +finalCompSection;
//			logger.debug("1:"+IQ1);

			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {	
					client.executeInsert(finalInsertQuery.toString(), subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = new HashMap<String,Object>();

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}
				compact.put("message", "Successfully added " + prefLabel);
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint to add a software parent (Operating System/Database Software/Software Package/Script Package) (7 fields only)
		 * @param 
		 * @return Result response
		 */		
		.post("/inventory/addSoftware",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);

			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: "+ body);

			String prePackagedsoftwareType;
			String uuid = IdGenerator.generateUUID();
			String componentType = (String) body.get("sys:componentTypeValue");//sys:OperatingSystem/sys:DatabaseSoftware/sys:SoftwarePackage/sys:ScriptPackage
			String componentTypeName = (String) body.get("sys:componentTypeName");//Operating System/Database Software/Software Package/Script Package
			prePackagedsoftwareType = (String) body.get("prePackagedsoftwareType");
			String description = (String) body.get("sys:description");
			String altLabel = (String) body.get("skos:altLabel");
			String prefLabel = (String) body.get("skos:prefLabel");

			//load template node insert from file
			String IQ1 = LoadResource.getResourceAsString("/scripts/just_prefixes.sprql");
			IQ1 += "<http://z2c.dts-inc.com/id/" + uuid + "> ";
			if(componentType != null && !componentType.isEmpty()){
			    IQ1 += "rdf:type " + componentType + ";\n";
			    if (componentType.equals("sys:OperatingSystem")) {IQ1 += "sys:hasDataTemplate <http://z2c.dts-inc.com/id/209f7737-6298-4679-910b-fcbc03d9f904>;\n";}
			    if (componentType.equals("sys:ScriptPackage"))   {IQ1 += "sys:hasDataTemplate <http://z2c.dts-inc.com/id/866d1a68-5738-4fd8-8f62-bc6a5efd2c92>;\n";}
			    if (componentType.equals("sys:DatabaseSoftware")){IQ1 += "sys:hasDataTemplate <http://z2c.dts-inc.com/id/8d966aa0-2225-4e5d-b0c1-6f144e7a7f8e>;\n";}
			    if (componentType.equals("sys:SoftwarePackage")) {IQ1 += "sys:hasDataTemplate <http://z2c.dts-inc.com/id/cd0c4c49-ffa0-4744-a2c6-e4cc71f961f4>;\n";}
		    }
			logger.debug("componentTypeName:"+ componentTypeName);
			if(componentTypeName != null && !componentTypeName.isEmpty()){
			    IQ1 += "sys:componentType \"" + componentTypeName + "\";\n";//ok
		    }
			
			
			
			switch (prePackagedsoftwareType){
			case "COTS": IQ1 += "sys:cots \"true\";\n" + "sys:gots \"false\";\n" + "sys:customApp \"false\";\n";break;
			case "GOTS": IQ1 += "sys:cots \"false\";\n" + "sys:gots \"true\";\n" + "sys:customApp \"false\";\n";break;
			default:     IQ1 += "sys:cots \"false\";\n" + "sys:gots \"false\";\n" + "sys:customApp \"true\";\n";break;
			}
			if(description != null && !description.isEmpty()){
				IQ1 += "sys:description \"" + description + "\";\n";
			}
			if(altLabel != null && !altLabel.isEmpty()){
				IQ1 += "skos:altLabel \"" + altLabel + "\";\n";
			}
			if(prefLabel != null && !prefLabel.isEmpty()){
				IQ1 += "skos:prefLabel \"" + prefLabel +  " " + uuid.substring(28,36) + "\";\n";
			}
//			IQ1 += "sys:status \"added\";\n";//Initially inserted nodes have status=added/
//			IQ1 += "sys:deleteProtect \"false\";\n";//Initially inserted nodes have delete protect=false
			IQ1 += "rdf:type sys:Component " + ".\n";
//			IQ1 += "ver:hasHead :" + uuid + "-TBD .\n";
			String finalInsertQuery = IQ1; //+ "\n" +finalCompSection;
			logger.debug("1:"+IQ1);

			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {	
					client.executeInsert(finalInsertQuery.toString(), subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = new HashMap<String,Object>();

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}
				compact.put("message", "Successfully added " + prefLabel);
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint to register a hardware (UPS/Physical Node{Physical Server}/Network Device/Storage Appliance) (fields are dependent upon the device type) 
		 * @param 
		 * @return Result response
		 */		
		.post("/inventory/registerHardware",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);

			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: "+ body);

//			String uuid = IdGenerator.generateUUID();
			String nodeId = (String) body.get("id");
			//Build delete status query - delete  sys:status ="added" (initially inserted hardware nodes will status=added)
			String IQ1 = LoadResource.getResourceAsString("/scripts/just_prefixes.sprql"); 
//		 	IQ1 += "construct {<" + nodeId + "> sys:status \"added\" .}\nwhere {<" + nodeId + ">  ?p ?o .}\n";
		 	IQ1 += "construct {<" + nodeId + "> sys:status ?status .}\nwhere {<" + nodeId + ">  ?p ?o;\n sys:status ?status .}\n";
		 	String finalDeleteQuery = IQ1;
//			logger.debug("Del:"+IQ1);
			//load template node insert from file
			IQ1 = LoadResource.getResourceAsString("/scripts/just_prefixes.sprql");
//			IQ1 += "<http://z2c.dts-inc.com/id/" + idToChange + "> ";
			IQ1 += "<" + nodeId + "> ";
//			String altLabel = (String) body.get("skos:altLabel");
//			String prefLabel = (String) body.get("skos:prefLabel");
//			String deleteProtect = (String) body.get("sys:deleteProtect");
//			String description = (String) body.get("sys:description");
			String deviceType = (String) body.get("sys:deviceType");//Physical server/UPS/ Network device/Storage appliance
			String endOfLife = (String) body.get("sys:endOfLife");
			if(endOfLife != null && !endOfLife.isEmpty()){
				IQ1 += "sys:endOfLife \"" + endOfLife + "\";\n";
			}
			String evaluatedAssuranceLevel = (String) body.get("sys:evaluatedAssuranceLevel");
			if(evaluatedAssuranceLevel != null && !evaluatedAssuranceLevel.isEmpty()){
				IQ1 += "sys:evaluatedAssuranceLevel \"" + evaluatedAssuranceLevel + "\";\n";
			}
			String fips = (String) body.get("sys:fips");
			if(fips != null && !fips.isEmpty()){
				IQ1 += "sys:fips \"" + fips + "\";\n";
			}
			String internetAccess = (String) body.get("sys:internetAccess");
			if(internetAccess != null && !internetAccess.isEmpty()){
				IQ1 += "sys:internetAccess \"" + internetAccess + "\";\n";
			}
			String logicalLocation = (String) body.get("sys:logicalLocation");
			if(logicalLocation != null && !logicalLocation.isEmpty()){
				IQ1 += "sys:logicalLocation \"" + logicalLocation + "\";\n";
			}
			String physicalLocation = (String) body.get("sys:physicalLocation");
			if(physicalLocation != null && !physicalLocation.isEmpty()){
				IQ1 += "sys:physicalLocation \"" + physicalLocation + "\";\n";
			}
			String role = (String) body.get("sys:role");
			if(role != null && !role.isEmpty()){
				IQ1 += "sys:role \"" + role + "\";\n";
			}
			String sensitiveInfo = (String) body.get("sys:sensitiveInfo");
			if(sensitiveInfo != null && !sensitiveInfo.isEmpty()){
				IQ1 += "sys:sensitiveInfo \"" + sensitiveInfo + "\";\n";
			}
			IQ1 += "sys:status \"registered\";\n";
			String vlan = (String) body.get("sys:vlan");
			if(vlan != null && !vlan.isEmpty()){
				IQ1 += "sys:vlan \"" + vlan + "\";\n";
			}
			String webServerType = (String) body.get("sys:webServerType");
			if(webServerType != null && !webServerType.isEmpty()){
				IQ1 += "sys:webServerType \"" + webServerType + "\";\n";
			}
			String ipAddress = (String) body.get("tag:ipAddress");
			if(ipAddress != null && !ipAddress.isEmpty()){
				IQ1 += "tag:ipAddress \"" + ipAddress + "\";\n";
			}
			if(deviceType.equals("Physical server")){
				String cores = (String) body.get("tag:cores");//Physical server only
				if(cores != null && !cores.isEmpty()){
					IQ1 += "tag:cores \"" + cores + "\";\n";
				}
				String cpus = (String) body.get("tag:cpus");//Physical server only
				if(cpus != null && !cpus.isEmpty()){
					IQ1 += "tag:cpus \"" + cpus + "\";\n";
				}
				String memory = (String) body.get("tag:memory");//Physical server only
				if(memory != null && !memory.isEmpty()){
					IQ1 += "tag:memory \"" + memory + "\";\n";
				}
		    }
//			String manufacturer = (String) body.get("tag:manufacturer");
//			String modelName = (String) body.get("tag:modelName");
			String vendor = (String) body.get("tag:vendor");
			if(vendor != null && !vendor.isEmpty()){
				IQ1 += "tag:vendor \"" + vendor + "\".\n";
			}

			String finalInsertQuery = IQ1;
			logger.debug("Ins:"+IQ1);

			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {	
					client.executeDelete(finalDeleteQuery, subscriber);	
					subscriber.onCompleted();
					client.executeInsert(finalInsertQuery.toString(), subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = new HashMap<String,Object>();

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}
				compact.put("message", "Successfully registered " + nodeId);
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint to delete a node - hardware (no version only)
		 * @param 
		 * @return Result response
		 */		
//		.post("/config/deleteNode",promise( (req, deferred) -> {
		.get("/inventory/deleteNode",promise( (req, deferred) -> {


			SparqlClient client = req.require(SparqlClient.class);

//			String nodeId = "<http://z2c.dts-inc.com/id/"+req.param("nodeId").value()+">";
			String nodeId = "<"+req.param("nodeId").value()+">";

			//This is the blazegraph query that deletes node info from blazegraph
			String deleteQuery = "describe ?nodeId";

			String finalQuery = deleteQuery.replaceAll("\\?nodeId", nodeId);
			logger.debug("Final query: {}", finalQuery);

			Observable.<String> create(subscriber -> {
				try {	
					client.executeDelete(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {	
				logger.debug("-->>> Result: "+ result);
				HashMap<String,Object> compact = new HashMap<String,Object>();

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){

					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();

						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);

						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("JsonLdError compacting: ",e);
					} catch (Exception e) {
						logger.error("Unhandled Error compacting: ",e);
					}

				}

				compact.put("message", "Successfully deleted node: " + nodeId);
				return compact;
			})
			.subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint to change the status of a node (used by hardware of inventory)
		 * @param 
		 * @return Result response
		 */		
		.post("/inventory/changeStatus",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);

			HashMap<String,Object> body = req.body().to(HashMap.class);
//			logger.debug("Incoming request: ", body);

//			String uuid = IdGenerator.generateUUID();
			String nodeId = (String) body.get("nodeId");
//			String nodeId = "http://z2c.dts-inc.com/id/7ca13377-e230-499f-9e63-8b5edb6d9c29";
			String status = (String) body.get("status");
			//load template node insert from file
			String IQ1 = LoadResource.getResourceAsString("/scripts/just_prefixes.sprql"); 
		 	IQ1 += "construct {<" + nodeId + "> sys:status ?status .}\nwhere {<" + nodeId + ">  ?p ?o;\n sys:status ?status .}\n";
		 	String finalDeleteQuery = IQ1;
//		 	logger.debug("1:"+finalDeleteQuery);
		 	IQ1 = LoadResource.getResourceAsString("/scripts/just_prefixes.sprql");
		 	IQ1 += "<" + nodeId + "> sys:status \"" + status + "\" .";
		 	String finalInsertQuery = IQ1;
//			logger.debug("2:"+finalInsertQuery);

			Observable.<String> create(subscriber -> {
				try {	
					client.executeDelete(finalDeleteQuery, subscriber);	
					subscriber.onCompleted();
					client.executeInsert(finalInsertQuery, subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
//				logger.debug("-->>> Result: "+ result);
				HashMap<String,Object> compact = new HashMap<String,Object>();

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}
				compact.put("message", "Successfully changed status ");
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint to add a device type (Physical server/UPS/ Network device/Storage appliance)
		 * @param 
		 * @return Result response
		 */		
		.post("/config/addDeviceType",promise( (req, deferred) -> {

			SparqlClient client = req.require(SparqlClient.class);

			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: ", body);

			String uuid = IdGenerator.generateUUID();
			String ontologyPrefix = (String) body.get("sys:ontologyPrefix");
			String systemName = (String) body.get("sys:systemName");
			String systemType = (String) body.get("sys:systemType");
			String altLabel = (String) body.get("skos:altLabel");
			String prefLabel = (String) body.get("skos:prefLabel");

			//load template node insert from file
			String IQ1 = LoadResource.getResourceAsString("/scripts/deviceTypeInsert.sprql");
			IQ1 = IQ1.replaceAll("\\?uuid", uuid);
			if(ontologyPrefix != null && !ontologyPrefix.isEmpty()){
				IQ1 += "sys:ontologyPrefix \"" + ontologyPrefix + "\";\n";
			}
			if(systemName != null && !systemName.isEmpty()){
				IQ1 += "sys:systemName \"" + systemName + "\";\n";
			}
			if(systemType != null && !systemType.isEmpty()){
				IQ1 += "sys:systemType \"" + systemType + "\";\n";
			}
			if(altLabel != null && !altLabel.isEmpty()){
				IQ1 += "skos:altLabel \"" + altLabel + "\";\n";
			}
			if(prefLabel != null && !prefLabel.isEmpty()){
				IQ1 += "skos:prefLabel \"" + prefLabel  + "\";\n";
			}
			IQ1 += "a sys:ListItem;\na sys:HardwareDeviceList\nsys:includes.";//here -needs a link as the object, 
//			for example <http://z2c.dts-inc.com/id/7573c9d7-022f-44eb-865a-bd3ea6cd8c7e> for Physical Node. 
//			Difficult as it is a new device type!
			String finalInsertQuery = IQ1; 
			logger.debug("1:"+IQ1);

			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {	
					client.executeInsert(finalInsertQuery.toString(), subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = new HashMap<String,Object>();

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}
				compact.put("message", "Successfully added " + prefLabel);
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))

		/**
		 * Endpoint to update a Component 
		 * @param 
		 * @return Result response
		 */		
		.post("/components/update",promise( (req, deferred) -> {


			SparqlClient client = req.require(SparqlClient.class);

			String nodeId = "<http://z2c.dts-inc.com/id/"+req.param("nodeId").value()+">";

			//This is the blazegraph query that deletes node info from blazegraph
			String deleteQuery = "describe ?nodeId";

			String finalDeleteQuery = deleteQuery.replaceAll("\\?nodeId", nodeId);


			HashMap<String,Object> body = req.body().to(HashMap.class);
			logger.debug("Incoming request: ", body);

			String uuid = (String) body.get("componentId");
			String componentType = (String) body.get("componentType");
			String prefLabel = (String) body.get("prefLabel");
			String versionNumber = (String) body.get("versionNumber");
			String altLabel = (String) body.get("altLabel");
			String packageName = (String) body.get("packageName");
			String puppetModule = (String) body.get("puppetModule");
			String puppetClass = (String) body.get("puppetClass");
			String componentId = (String) body.get("componentId");

			String insertQuery = LoadResource.getResourceAsString("/scripts/component_insert.sprql");
			insertQuery = insertQuery.replaceAll("\\?uuid", uuid);
			insertQuery = insertQuery.replaceAll("\\?componentType", componentType);
			insertQuery = insertQuery.replaceAll("\\?prefLabel", prefLabel);
			insertQuery = insertQuery.replaceAll("\\?versionNumber", versionNumber);
			insertQuery = insertQuery.replaceAll("\\?altLabel", altLabel);
			insertQuery = insertQuery.replaceAll("\\?packageName", packageName);
			insertQuery = insertQuery.replaceAll("\\?puppetModule", puppetModule);
			insertQuery = insertQuery.replaceAll("\\?puppetClass", puppetClass);
			insertQuery = insertQuery.replaceAll("\\?componentId", componentId);

			String finalInsertQuery = insertQuery;
			logger.debug(insertQuery);
			//In all instances, executeQuery must be run before executeInsert in order for BG to get updated with insert.		
			Observable.<String> create(subscriber -> {
				try {
					//execute the delete query before the insert
					client.executeDelete(finalDeleteQuery, subscriber);
					subscriber.onCompleted();

					subscriber.onStart();
					client.executeInsert(finalInsertQuery, subscriber);	
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {
				HashMap<String,Object> compact = null;


				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}

				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))


		/**
		 * Endpoint to delete a component given component ID
		 * @param orgId
		 *            UUID of the component
		 * @return Result response
		 */		
		.get("/components/delete/:nodeId",promise( (req, deferred) -> {


			SparqlClient client = req.require(SparqlClient.class);

			String nodeId = "<http://z2c.dts-inc.com/id/"+req.param("nodeId").value()+">";

			//This is the blazegraph query that deletes node info from blazegraph
			String deleteQuery = "describe ?nodeId";

			String finalQuery = deleteQuery.replaceAll("\\?nodeId", nodeId);
			logger.debug("Final query: {}", finalQuery);

			Observable.<String> create(subscriber -> {
				try {	
					client.executeDelete(finalQuery, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}				
			}).map(result -> {	
				logger.debug("-->>> Result: "+ result);
				HashMap<String,Object> compact = null;

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){

					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();

						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);

						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("JsonLdError compacting: ",e);
					} catch (Exception e) {
						logger.error("Unhandled Error compacting: ",e);
					}

				}

				return compact;
			})
			.subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))


		.get("/getDocumentTemplates",promise( (req, deferred) -> {

			String queryString = LoadResource.getResourceAsString("/scripts/get_document_templates.sprql");

			SparqlClient client = req.require(SparqlClient.class);

			Observable.<String> create(subscriber -> {
				try {
					client.executeQuery(queryString, subscriber);
					subscriber.onCompleted();
				} catch (Exception e) {
					subscriber.onError(e);
				}
			}).map(result -> {
				HashMap<String,Object> compact = null;
				try {
					Object jsonObject = JsonUtils.fromString(result);
					HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
					JsonLdOptions options = new JsonLdOptions();
					compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
					compact.remove("@context");
				} catch (JsonLdError e) {
					logger.error("Error compacting: ",e);
				} catch (Exception e) {
					logger.error("Error compacting: ",e);
				}
				return compact;
			}).subscribeOn(Schedulers.computation()).subscribe(deferred::resolve, deferred::reject);
		}))

		.post("/upload",promise( (req, deferred) -> {

			Upload upload = req.file("file");
			logger.debug("Upload name is " + upload.name());
			logger.debug("Upload type is " + upload.type());
			logger.debug("Upload length is " + upload.file().length());

			String uploadFileName = upload.name();
			String uploadFileType = String.valueOf(upload.type());
			String uploadFileSize = String.valueOf(upload.file().length());

			File file = upload.file();
			byte[] bytesArray = new byte[(int) file.length()];
			FileInputStream fis = new FileInputStream(file);
			fis.read(bytesArray); //read file into bytes[]

			logger.debug("number of bytes is.... " + bytesArray.length);
			//THIS DIRECTORY MUST BE ABLE TO BE CREATED AND WRITTEN TO
			//ON THE Z2C SERVER!!!!!
			String fileDir = "\\tmp\\cmo\\appData\\";
			String uuid = IdGenerator.generateUUID();

			Path newFile = Paths.get(fileDir + upload.name());
			Files.createDirectories(Paths.get(fileDir));
			Files.write(newFile, bytesArray);
			upload.close();

			String queryString = "construct where { <http://z2c.dts-inc.com/id/737359e4-7f9a-43c2-b449-d22563ae1862> ?p ?o .}";
			String insertTtl = LoadResource.getResourceAsString("/scripts/repository_insert.sprql");

			logger.debug("Final query before replace is " + insertTtl);
			logger.debug("UUID is  " + uuid);
			logger.debug("uploadFileName is  " + uploadFileName);
			logger.debug("fileDir is  " + fileDir);
			logger.debug("uploadFileSize is  " + uploadFileSize);
			logger.debug("uploadFileType is  " + uploadFileType);

			logger.debug("Insert query is " + insertTtl);
			insertTtl = insertTtl.replaceAll("\\?uuId", uuid);
			insertTtl = insertTtl.replaceAll("\\?fileName", uploadFileName);
			//insertTtl = insertTtl.replaceAll("\\?filePath", fileDir);
			insertTtl = insertTtl.replaceAll("\\?fileSize", uploadFileSize);
			insertTtl = insertTtl.replaceAll("\\?fileType", uploadFileType);
			logger.debug("Insert query is now " + insertTtl);

			String finalInsertQuery = insertTtl;

			RxSparqlClient client = req.require(RxSparqlClient.class);
			client.turtleInsert(finalInsertQuery)
			.map(result -> {
				logger.debug("-->>> Result: "+ result);
				HashMap<String,Object> compact = null;

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}

				return compact;
			})
			.subscribeOn(Schedulers.computation())
			.subscribe(deferred::resolve, deferred::reject);
		}))


		.get("/download/:fileName",  (req, rsp) -> {

			String storageLocation = TMP_STORAGE_LOC;
			//HashMap<String,Object> body = (HashMap<String, Object>) req.body().to(Object.class);
			//String fileName = (String) body.get("fileName");
			String fileName = storageLocation + req.param("fileName").value();

			rsp
			.type("application/msword")
			.header("Content-Disposition", "attachment; filename="+fileName)
			.status(200)
			.download(new File(fileName));
			
		})




		.get("/test",promise( (req, deferred) -> {
			String queryString = "construct where { <http://z2c.dts-inc.com/id/737359e4-7f9a-43c2-b449-d22563ae1862> ?p ?o .}";
			RxSparqlClient client = req.require(RxSparqlClient.class);
			client.constructQuery(queryString)
			.map(result -> {
				logger.debug("-->>> Result: "+ result);
				HashMap<String,Object> compact = null;

				//We only want compact JSON data...inserts and deletes return XML
				if (!result.startsWith("<?xml")){
					try {
						Object jsonObject = JsonUtils.fromString(result);
						HashMap<String,Object> context = (HashMap<String, Object>) JsonUtils.fromInputStream(this.getClass().getResourceAsStream("/context.json"));
						JsonLdOptions options = new JsonLdOptions();
						compact = (HashMap<String, Object>) JsonLdProcessor.compact(jsonObject, context, options);
						compact.remove("@context");
						compact.remove("id");
					} catch (JsonLdError e) {
						logger.error("Error compacting: ",e);
					} catch (Exception e) {
						logger.error("Error compacting: ",e);
					}
				}

				return compact;
			})
			.subscribeOn(Schedulers.computation())
			.subscribe(deferred::resolve, deferred::reject);
		}));		
	}



	public static void main(final String[] args) throws Exception {
		try {
			run(App::new, args);
		} catch (Throwable e) {
			logger.error("Error running service: ",e);
		}
		System.exit(0); 
	}


}
