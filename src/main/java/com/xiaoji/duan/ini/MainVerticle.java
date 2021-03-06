package com.xiaoji.duan.ini;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;

public class MainVerticle extends AbstractVerticle {

	private MongoClient mongodb = null;
	private List<String> tablenames = new ArrayList<String>();

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		
		// 可以配置通过mongodb或者file获取配置参数
		if ("mongodb".equals(config().getString("source", "mongodb"))) {
			JsonObject config = new JsonObject();
			config.put("host", "mongodb");
			config.put("port", 27017);
			config.put("keepAlive", true);
			mongodb = MongoClient.createShared(vertx, config);
		}
		
		init();
		
		Router router = Router.router(vertx);
		
		Set<HttpMethod> allowedMethods = new HashSet<HttpMethod>();
		allowedMethods.add(HttpMethod.OPTIONS);
		allowedMethods.add(HttpMethod.GET);
		allowedMethods.add(HttpMethod.POST);
		allowedMethods.add(HttpMethod.PUT);
		allowedMethods.add(HttpMethod.DELETE);
		allowedMethods.add(HttpMethod.CONNECT);
		allowedMethods.add(HttpMethod.PATCH);
		allowedMethods.add(HttpMethod.HEAD);
		allowedMethods.add(HttpMethod.TRACE);

		router.route().handler(CorsHandler.create("*")
				.allowedMethods(allowedMethods)
				.allowedHeader("*")
				.allowedHeader("Content-Type")
				.allowedHeader("lt")
				.allowedHeader("pi")
				.allowedHeader("pv")
				.allowedHeader("di")
				.allowedHeader("dt")
				.allowedHeader("ai"));

		router.route("/ini/parameters").handler(BodyHandler.create());

		router.route("/ini/parameters").handler(this::initparams);

		HttpServerOptions option = new HttpServerOptions();
		option.setCompressionSupported(true);

		vertx.createHttpServer(option).requestHandler(router::accept).listen(config().getInteger("port", 8080), http -> {
			if (http.succeeded()) {
				startFuture.complete();
				System.out.println("HTTP server started on http://localhost:" + config().getInteger("port", 8080));
			} else {
				startFuture.fail(http.cause());
			}
		});
	}
	
	private void init() {
		tablenames.add("apil");
		tablenames.add("bipl");
		tablenames.add("vrs");
		tablenames.add("dpfu");
	}

	private void initparams(RoutingContext ctx) {
		JsonObject ret = new JsonObject();
		ret.put("rc", "0");
		ret.put("rm", "");
		ret.put("d", new JsonObject());
		
		String productid = ctx.request().getHeader("pi");
		String productversion = ctx.request().getHeader("pv");
		
		if (productid == null || StringUtils.isEmpty(productid) || productversion == null || StringUtils.isEmpty(productversion)) {
			ret.put("rc", "-2");
			ret.put("rm", "非法请求!");

			ctx.response().putHeader("Content-Type", "application/json;charset=UTF-8").end(ret.encode());
			return;
		}
		
		String debug = ctx.request().getParam("debug");
		Boolean isDebug = false;

		if (!StringUtils.isEmpty(debug) && "true".equals(debug)) {
			isDebug = true;
		}
		
		String tag = ctx.request().getParam("tag");

		if (!StringUtils.isEmpty(tag) && !"mwxing".equals(tag)) {
			tag = "mwxing";
		}
		
		List<Future<JsonObject>> futures = new LinkedList<>();
		
		for (String tablename : tablenames) {
			Future<JsonObject> future = Future.future();
			futures.add(future);

			if ("mongodb".equals(config().getString("source", "mongodb"))) {
				queryparams(future, productid, productversion, tablename, isDebug, tag);
			}

			if ("file".equals(config().getString("source", "mongodb"))) {
				queryparamsfromfile(future, productid, productversion, tablename, isDebug, tag);
			}
		}
		
		if (futures.size() > 0) {
			CompositeFuture.all(Arrays.asList(futures.toArray(new Future[futures.size()])))
			.map(v -> futures.stream().map(Future::result).collect(Collectors.toList()))
			.setHandler(handler -> {
				if (handler.succeeded()) {
					List<JsonObject> results = handler.result();

					JsonObject retdata = new JsonObject();
					
					for (Object result : results) {
						if (result instanceof JsonObject) {
							JsonObject params = (JsonObject) result;
							
							retdata.put(params.getString("tablename"), params.getJsonArray("tablevalues", new JsonArray()));
						}
					}
					
					ret.put("d", retdata);
					
					ctx.response().putHeader("Content-Type", "application/json;charset=UTF-8").end(ret.encode());
				} else {
					ret.put("rc", "-3");
					ret.put("rm", handler.cause().getMessage());
					
					ctx.response().putHeader("Content-Type", "application/json;charset=UTF-8").end(ret.encode());
				}
			});
		} else {
			ctx.response().putHeader("Content-Type", "application/json;charset=UTF-8").end(ret.encode());
		}
		
	}
	
	private void queryparamsfromfile(Future<JsonObject> future, String productid, String productversion, String tablename, Boolean isDebug, String tag) {
		String iniroot = config().getString("source-path", "/opt/duan/ini");
		String iniFile = iniroot + "/" + productid + "/" + tablename + ".json";
		
		if (!StringUtils.isEmpty(tag)) {
			iniFile = iniroot + "/" + tag + "/" + productid + "/" + tablename + ".json";

			if (isDebug) {
				iniFile = iniroot + "/" + tag + "/" + "/debug/" + productid + "/" + tablename + ".json";
			}
		} else {

			if (isDebug) {
				iniFile = iniroot + "/debug/" + productid + "/" + tablename + ".json";
			}
		}
		
		vertx.fileSystem().readFile(iniFile, handler -> {
			if (handler.succeeded()) {
				Buffer result = handler.result();
				
				JsonArray parameters = null;
				
				if (result == null || result.length() < 2) {
					parameters = new JsonArray();
				} else {
					try {
						parameters = result.toJsonArray();
					} catch(Exception e) {
						e.printStackTrace();
						parameters = new JsonArray();
					}
				}
				
				future.complete(new JsonObject()
						.put("tablename", tablename)
						.put("tablevalues", parameters)
						);
			} else {
				future.fail(handler.cause());
			}
		});
	}
	
	private void queryparams(Future<JsonObject> future, String productid, String productversion, String tablename, Boolean isDebug, String tag) {
		mongodb.find("ini_" + productid + "_" + tablename, new JsonObject(), find -> {
			if (find.succeeded()) {
				future.complete(new JsonObject()
						.put("tablename", tablename)
						.put("tablevalues", find.result())
						);
			} else {
				future.fail(find.cause());
			}
		});
	}
}
