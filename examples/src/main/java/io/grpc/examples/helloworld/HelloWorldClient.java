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

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple client that requests a greeting from the {@link HelloWorldServer}.
 */
public class HelloWorldClient {
  private static final Logger logger = Logger.getLogger(HelloWorldClient.class.getName());

  private final ManagedChannel channel;
  final TheOther98Grpc.TheOther98BlockingStub blockingStub;

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public HelloWorldClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port)
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        .usePlaintext()
        .build());
  }

  /** Construct client for accessing HelloWorld server using the existing channel. */
  HelloWorldClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = TheOther98Grpc.newBlockingStub(channel);
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  Profile getProfile(String handle) {
    if (client == null) return null;
    return client.blockingStub.getProfile(Handle.newBuilder().setValue(handle).build());
  }

  PostView getPost(String id) {
    if (client == null) return null;
    return client.blockingStub.getPost(Id.newBuilder().setValue(id).build());
  }

  Result createPost(String tag, PostSmallView postSmallView, List<ContentBlock> contentBlocks) {
    if (client == null) return null;
    Post.Builder builder = Post.newBuilder().setPostSmallView(postSmallView).addPostTags(tag).addAllContentBlocks(contentBlocks);
    return client.blockingStub.createPost(builder.build());
  }

  Iterator<PostFeedView> getFeed(List<String> tags) {
    if (client == null) return null;
    return blockingStub.getFeed(FeedType.newBuilder().addAllPostTags(tags).build());
  }

  Result createComment(String postViewId, Comment comment) {
    if (client == null) return null;
    return blockingStub.createComment(comment.toBuilder().setPostViewId(postViewId).build());
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting.
   */
  static HelloWorldClient client;
  public static void main(String[] args) throws Exception {
    client = new HelloWorldClient("localhost", 27017);
    try {
//      try {
//        PostView post = client.blockingStub.getPost(Id.newBuilder().setValue(4).build());
//        logger.info(post.getPostSmallView().getTitle());
//        logger.info(post.getPostSmallView().getAuthorHandle());
//      } catch (Exception e) {
//        logger.log(Level.WARNING, e.getMessage());
//      }

//      try {
//        Profile profile = client.getProfile("alex");
//        logger.info(profile.getFirstName());
//      } catch (Exception e) {
//        logger.log(Level.WARNING, e.getMessage());
//      }

//      try {
//        PostSmallView postSmallView = PostSmallView.newBuilder().setTitle("Test").setAuthorHandle("alex").build();
//        Result result = client.createPost("forum", postSmallView, new ArrayList<ContentBlock>());
//        logger.info(result.getStatusCode().toString());
//      } catch (Exception e) {
//        logger.log(Level.WARNING, e.getMessage());
//      }


      try {
        ArrayList<String> postTags = new ArrayList<>();
        postTags.add("forum");
        Iterator<PostFeedView> postFeedViews = client.getFeed(postTags);
        while (postFeedViews.hasNext()) {
          PostFeedView postFeedView = postFeedViews.next();
          logger.info("Number of comments before insert: " + Long.toString(postFeedView.getNumberOfComments()));
          PostView postView = client.getPost(postFeedView.getPostViewId());
          if (postView != null) {
            Comment.Builder builder = Comment.newBuilder().setAuthorHandle("avi");
            ArrayList<ContentBlock> contentBlocks = new ArrayList<>();
            contentBlocks.add(ContentBlock.newBuilder()
                    .setType(ContentBlock.ContentBlockType.Text)
                    .setContent("Test comment").build());
            builder.addAllContentBlocks(contentBlocks);
            Result createCommentResult = client.createComment(postView.getId(), builder.build());
            logger.info("Create comment result " + createCommentResult.getStatusCode().toString());

            postView = client.getPost(postFeedView.getPostViewId());
            logger.info("Number of comments after insert: " + Integer.toString(postView.getCommentsCount()));
            break;
          }
        }
      } catch (Exception e) {
        logger.warning(e.getLocalizedMessage());
      }

    } finally {
      client.shutdown();
    }
  }
}
