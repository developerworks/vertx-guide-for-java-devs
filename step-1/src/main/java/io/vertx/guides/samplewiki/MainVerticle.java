/*
 *  Copyright (c) 2016 Red Hat, Inc. and/or its affiliates.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vertx.guides.samplewiki;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author <a href="https://julien.ponge.org/">Julien Ponge</a>
 */
public class MainVerticle extends AbstractVerticle {

  private static final String SQL_CREATE_PAGES_TABLE = "create table if not exists Pages (Id integer identity primary key, Name varchar(255) unique, Content clob)";
  private static final String SQL_GET_PAGE = "select Id, Content from Pages where Name = ?";
  private static final String SQL_CREATE_PAGE = "insert into Pages values (NULL, ?, ?)";
  private static final String SQL_SAVE_PAGE = "update Pages set Content = ? where Id = ?";
  private static final String SQL_ALL_PAGES = "select Name from Pages";
  private static final String SQL_DELETE_PAGE = "delete from Pages where Id = ?";

  private static final String EMPTY_PAGE_MARKDOWN =
    "# A new page\n" +
      "\n" +
      "Feel-free to write in Markdown!\n";

  private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

  private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();

  private JDBCClient dbClient;

  private Future<Void> prepareDatabase() {
    Future<Void> future = Future.future();

    dbClient = JDBCClient.createShared(vertx, new JsonObject()
      .put("url", "jdbc:hsqldb:file:db/wiki")
      .put("driver_class", "org.hsqldb.jdbcDriver")
      .put("max_pool_size", 30));

    dbClient.getConnection(ar -> {
      if (ar.failed()) {
        logger.error("Could not open a database connection", ar.cause());
        future.fail(ar.cause());
      } else {
        SQLConnection connection = ar.result();
        connection.execute(SQL_CREATE_PAGES_TABLE, create -> {
          connection.close();
          if (create.failed()) {
            logger.error("Database preparation error", create.cause());
            future.fail(create.cause());
          } else {
            future.complete();
          }
        });
      }
    });

    return future;
  }

  private Future<Void> startHttpServer() {
    Future<Void> future = Future.future();
    HttpServer server = vertx.createHttpServer();

    Router router = Router.router(vertx);
    router.get("/").handler(indexHandler());
    router.get("/wiki/:page").handler(pageRenderingHandler());
    router.post().handler(BodyHandler.create());
    router.post("/save").handler(pageUpdateHandler());
    router.post("/create").handler(pageCreateHandler());
    router.post("/delete").handler(pageDeletionHandler());

    server
      .requestHandler(router::accept)
      .listen(8080, ar -> {
        if (ar.succeeded()) {
          logger.info("HTTP server running on port 8080");
          future.complete();
        } else {
          logger.error("Could not start a HTTP server", ar.cause());
          future.fail(ar.cause());
        }
      });

    return future;
  }

  private Handler<RoutingContext> pageDeletionHandler() {
    return context -> {
      String id = context.request().getParam("id");
      dbClient.getConnection(car -> {
        if (car.succeeded()) {
          SQLConnection connection = car.result();
          connection.updateWithParams(SQL_DELETE_PAGE, new JsonArray().add(id), res -> {
            connection.close();
            if (res.succeeded()) {
              context.response().setStatusCode(303);
              context.response().putHeader("Location", "/");
              context.response().end();
            } else {
              context.fail(res.cause());
            }
          });
        } else {
          context.fail(car.cause());
        }
      });
    };
  }

  private Handler<RoutingContext> pageCreateHandler() {
    return context -> {
      String pageName = context.request().getParam("name");
      String location = "/wiki/" + pageName;
      if (pageName == null || pageName.isEmpty()) {
        location = "/";
      }
      context.response().setStatusCode(303);
      context.response().putHeader("Location", location);
      context.response().end();
    };
  }

  private Handler<RoutingContext> indexHandler() {
    return context -> {
      dbClient.getConnection(car -> {
        if (car.succeeded()) {
          SQLConnection connection = car.result();
          connection.query(SQL_ALL_PAGES, res -> {
            connection.close();

            if (res.succeeded()) {
              List<String> pages = res.result()
                .getResults()
                .stream()
                .map(json -> json.getString(0))
                .sorted()
                .collect(Collectors.toList());

              context.put("title", "Wiki home");
              context.put("pages", pages);
              templateEngine.render(context, "templates/index.ftl", ar -> {
                if (ar.succeeded()) {
                  context.response().putHeader("Content-Type", "text/html");
                  context.response().end(ar.result());
                } else {
                  context.fail(ar.cause());
                }
              });

            } else {
              context.fail(res.cause());
            }
          });
        } else {
          context.fail(car.cause());
        }
      });
    };
  }

  private Handler<RoutingContext> pageUpdateHandler() {
    return context -> {
      String id = context.request().getParam("id");
      String title = context.request().getParam("title");
      String markdown = context.request().getParam("markdown");
      boolean newPage = "yes".equals(context.request().getParam("newPage"));

      dbClient.getConnection(car -> {
        if (car.succeeded()) {
          SQLConnection connection = car.result();
          String sql = newPage ? SQL_CREATE_PAGE : SQL_SAVE_PAGE;
          JsonArray params = new JsonArray();
          if (newPage) {
            params.add(title).add(markdown);
          } else {
            params.add(markdown).add(id);
          }
          connection.updateWithParams(sql, params, res -> {
            connection.close();
            if (res.succeeded()) {
              context.response().setStatusCode(303);
              context.response().putHeader("Location", "/wiki/" + title);
              context.response().end();
            } else {
              context.fail(res.cause());
            }
          });
        } else {
          context.fail(car.cause());
        }
      });
    };
  }

  private Handler<RoutingContext> pageRenderingHandler() {
    return context -> {
      String page = context.request().getParam("page");

      dbClient.getConnection(car -> {
        if (car.succeeded()) {

          SQLConnection connection = car.result();
          connection.queryWithParams(SQL_GET_PAGE, new JsonArray().add(page), fetch -> {
            connection.close();
            if (fetch.succeeded()) {
              JsonArray row = fetch.result().getResults()
                .stream()
                .findFirst()
                .orElseGet(() -> new JsonArray().add(-1).add(EMPTY_PAGE_MARKDOWN));
              Integer id = row.getInteger(0);
              String rawContent = row.getString(1);

              context.put("title", page);
              context.put("id", id);
              context.put("newPage", fetch.result().getResults().size() == 0 ? "yes" : "no");
              context.put("rawContent", rawContent);
              context.put("content", Processor.process(rawContent));
              context.put("timestamp", new Date().toString());

              templateEngine.render(context, "templates/page.ftl", ar -> {
                if (ar.succeeded()) {
                  context.response().putHeader("Content-Type", "text/html");
                  context.response().end(ar.result());
                } else {
                  context.fail(ar.cause());
                }
              });
            } else {
              context.fail(fetch.cause());
            }
          });

        } else {
          context.fail(car.cause());
        }
      });
    };
  }

  @Override
  public void start(Future<Void> startFuture) throws Exception {
    prepareDatabase()
      .compose(v -> startHttpServer())
      .compose(v -> startFuture.complete(), startFuture);
  }
}