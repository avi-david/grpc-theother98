/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.helloworld;

import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolStringList;
import com.google.protobuf.util.JsonFormat;
import com.mongodb.MongoClient;
import com.mongodb.MongoServerException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.or;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class HelloWorldServer {
  private static final Logger logger = Logger.getLogger(HelloWorldServer.class.getName());

  private Server server;
  private MongoClient mongoClient;
  MongoDatabase theOther98Database;

  private void start() throws IOException {
    int port = 27017;
    mongoClient = new MongoClient("localhost", port);

    theOther98Database = mongoClient.getDatabase("theOther98Test");
//    FindIterable<Document> collection = mongoDatabase.getCollection("profiles").find();
    /* The port on which the server should run */
    server = ServerBuilder.forPort(port)
        .addService(new GreeterImpl())
        .build()
        .start();
    logger.info("MongoServer started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        HelloWorldServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() {
    mongoClient.close();
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  static HelloWorldServer serverInstance;

  private static class CollectionProfiles {
    private static final String collectionName = "profiles";
    private static MongoCollection<Document> collection = serverInstance.theOther98Database.getCollection(collectionName);
    private static final class FieldNames {
      final static String Handle = "handle";
    }
  }
  private static class CollectionPosts {
    private static final String collectionName = "posts";
    private static MongoCollection<Document> getCollection(HelloWorldServer serverInstance) {
      return serverInstance.theOther98Database.getCollection(collectionName);
    }
    private static final class FieldNames {
      final static String Id = "_id";
      final static String PostTags = "postTags";
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    serverInstance = new HelloWorldServer();
    serverInstance.start();
    serverInstance.blockUntilShutdown();
  }

  static class GreeterImpl extends TheOther98Grpc.TheOther98ImplBase {
    @Override
    public void getFeed(FeedType request, StreamObserver<PostFeedView> responseObserver) {
      ArrayList<PostFeedView> postsResultList = new ArrayList<>();
      Bson filter = in(CollectionPosts.FieldNames.PostTags, request.getPostTagsList());
      MongoCursor<Document> cursor = CollectionPosts.getCollection(serverInstance).find(filter).iterator();
      while (cursor.hasNext()) {
        Document postDocument = cursor.next();
        String postString = postDocument.toJson();
        logger.info(postString);
        PostView post;
        try {
          post = convertJsonToPostView(postString, PostView.newBuilder());
        } catch (Exception e) {
          post = null;
          logger.warning(e.getMessage());
        }
        if (post != null) {
          PostFeedView.Builder builder = PostFeedView.newBuilder().setPostSmallView(post.getPostSmallView());
          builder.setPostViewId(getObjectIdString(postDocument));
          builder.setNumberOfComments(post.getCommentsCount());
//          builder.setDateOfLastComment(post.getCommentsList().get(post.getCommentsCount() - 1))
          postsResultList.add(builder.build());
        }
      }
      for (PostFeedView post : postsResultList) {
        logger.info(post.getPostSmallView().getTitle());
        responseObserver.onNext(post);
      }
      responseObserver.onCompleted();
//      super.getFeed(request, responseObserver);
    }

    @Override
    public void getPost(Id request, StreamObserver<PostView> responseObserver) {
      if (serverInstance.theOther98Database != null) {
        MongoCollection<Document> posts = CollectionPosts.getCollection(serverInstance);

        Bson filter = eq(CollectionPosts.FieldNames.Id, new ObjectId(request.getValue()));
        MongoCursor<Document> cursor = posts.find(filter).iterator();

        if (!cursor.hasNext()) {
          logger.warning("No postView found for getPost request: " + request.toString());
          responseObserver.onNext(null);
          responseObserver.onCompleted();
        } else {
          while (cursor.hasNext()) {
            Document document = cursor.next();
            PostView postView ;
            try {
              postView = convertJsonToPostView(document.toJson(), PostView.newBuilder());
            } catch (Exception e) {
              postView = null;
              logger.warning("Error while parsing " + document.toJson() + " into message");
            }
            if (postView != null) {
              responseObserver.onNext(postView.toBuilder().setId(getObjectIdString(document)).build());
            } else {
              responseObserver.onNext(null);
            }
            responseObserver.onCompleted();
          }
        }
      }

    }

    @Override
    public void createPost(Post request, StreamObserver<Result> responseObserver) {
      if (serverInstance.theOther98Database != null) {
        ProtocolStringList tags = request.getPostTagsList();
        ArrayList<String> modifiedTags = new ArrayList<>(tags);
        //convert all tags to lowercase
        for (String tag: tags) {
          int i = tags.indexOf(tag);
          modifiedTags.set(i, tags.get(i).toLowerCase());
        }
        request = request.toBuilder().clearPostTags().addAllPostTags(tags).build();

        MongoCollection<Document> posts = serverInstance.theOther98Database.getCollection(CollectionPosts.collectionName);

        //convert to postview
        PostView.Builder builder = PostView.newBuilder()
                .setPostSmallView(request.getPostSmallView())
                .addAllPostTags(request.getPostTagsList())
                .addAllContentBlocks(request.getContentBlocksList());
        builder.addAllComments(new ArrayList<>());

        //parse postview for insertion
        Document document = Document.parse(convertMessageToJson(builder.build()));

        logger.info(document.toJson());
        try {
          posts.insertOne(document);
          logger.info("post created");
          responseObserver.onNext(Result.newBuilder().setStatusCode(Result.StatusCode.OK).build());
        }
        catch (MongoServerException ex) {
          logger.warning(ex.getLocalizedMessage());
          responseObserver.onNext(Result.newBuilder().setStatusCode(Result.StatusCode.FORBIDDEN).build());
        }
        responseObserver.onCompleted();
      }
    }

    @Override
    public void getProfile(Handle request, StreamObserver<Profile> responseObserver) {
      if (serverInstance.theOther98Database != null) {
        MongoCollection<Document> profiles = serverInstance.theOther98Database.getCollection(CollectionProfiles.collectionName);
        Bson filter = eq(CollectionProfiles.FieldNames.Handle, request.getValue());
        MongoCursor<Document> cursor = profiles.find(filter).iterator();
        while (cursor.hasNext()) {
          Document doc = cursor.next();
          String jsonString = doc.toJson();
          Profile profile;
          try {
            profile = convertJsonToProfile(jsonString, Profile.newBuilder());
          } catch (Exception e) {
            profile = null;
            Logger.getAnonymousLogger().warning("Error parsing profile with handle " + request.getValue());
          }
          if (profile != null) {
            responseObserver.onNext(profile);
            responseObserver.onCompleted();
            cursor.close();
            break;
          } else {
            Logger.getAnonymousLogger().warning("No profile found with handle " + request.getValue());
            responseObserver.onNext(null);
            responseObserver.onCompleted();
          }
        }
      }
    }

    @Override
    public void createComment(Comment request, StreamObserver<Result> responseObserver) {
      if (serverInstance.theOther98Database != null) {
        MongoCollection<Document> posts = CollectionPosts.getCollection(serverInstance);

        if (request.getPostViewId() != null) {
          Bson filter = eq(CollectionPosts.FieldNames.Id, new ObjectId(request.getPostViewId()));
          MongoCursor<Document> cursor = posts.find(filter).iterator();
          if (!cursor.hasNext()) {
            logger.warning("No postView found for createComment request: " + request.toString());
            responseObserver.onNext(Result.newBuilder().setStatusCode(Result.StatusCode.NOT_FOUND).build());
            responseObserver.onCompleted();
          }
          while (cursor.hasNext()) {
            Document doc = cursor.next();
            PostView postView;
            try {
              postView = convertJsonToPostView(doc.toJson(), PostView.newBuilder());
            } catch (Exception e) {
              postView = null;
              Logger.getAnonymousLogger().warning("Error parsing post with ID " + request.getPostViewId());
            }
            if (postView != null) {
              PostView.Builder builder = postView.toBuilder();
              ArrayList<Comment> comments = new ArrayList<>(builder.getCommentsList());
              comments.add(request.toBuilder()
                      .setId(builder.getCommentsCount())
                      .setScore(0)
                      .setCreateDateMillis(Instant.now().toEpochMilli())
                      .build());
              builder.clearComments().addAllComments(comments);
              postView = builder.build();

              UpdateResult result = posts.replaceOne(filter, Document.parse(convertMessageToJson(postView)));
              if (result.getModifiedCount() == 1) {
                responseObserver.onNext(Result.newBuilder().setStatusCode(Result.StatusCode.OK).build());
              } else {
                logger.warning(result.toString());
                responseObserver.onNext(Result.newBuilder().setStatusCode(Result.StatusCode.INTERNAL_ERROR).build());
              }
              responseObserver.onCompleted();
              break;
            }
          }
        } else {
          logger.warning("No postView ID attached for createComment request: " + request.toString());
          responseObserver.onNext(Result.newBuilder().setStatusCode(Result.StatusCode.NOT_FOUND).build());
          responseObserver.onCompleted();
        }
      }
//      super.createComment(request, responseObserver);
    }
  }

  private static Profile convertJsonToProfile(String jsonString, Profile.Builder builder) throws InvalidProtocolBufferException {
    JsonFormat.parser().ignoringUnknownFields().merge(jsonString, builder);
    return builder.build();
  }

  private static PostSmallView convertJsonToPostSmallView(String jsonString, PostSmallView.Builder builder) throws InvalidProtocolBufferException {
    JsonFormat.parser().ignoringUnknownFields().merge(jsonString, builder);
    return builder.build();
  }

  private static PostView convertJsonToPostView(String jsonString, PostView.Builder builder) throws InvalidProtocolBufferException {
    JsonFormat.parser().ignoringUnknownFields().merge(jsonString, builder);
    return builder.build();
  }

  private static Post convertJsonToPost(String jsonString, Post.Builder builder) throws InvalidProtocolBufferException {
    JsonFormat.parser().ignoringUnknownFields().merge(jsonString, builder);
    return builder.build();
  }

  private static String convertMessageToJson(Message message) {
    String returnValue = new Gson().toJson(message);
    try {
      returnValue = JsonFormat.printer().print(message);
    } catch (Exception e) {

    }
    return returnValue;
  }

  private static String getObjectIdString(Document document) {
    return document.getObjectId("_id").toString();
  }
}
