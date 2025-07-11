package minispark.distributed.objectstore;

import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.LinkedHashMap;

import minispark.distributed.network.MessageBus;
import minispark.distributed.network.NetworkEndpoint;
import minispark.distributed.messages.*;
import minispark.distributed.objectstore.serialization.ObjectStoreSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public class Client implements MessageBus.MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);
    private final MessageBus messageBus;
    private final NetworkEndpoint clientEndpoint;
    private final HashRing hashRing;
    private final LinkedHashMap<String, CompletableFuture<Object>> pendingRequests;

    public Client(MessageBus messageBus, NetworkEndpoint clientEndpoint, List<NetworkEndpoint> serverEndpoints) {
        this.messageBus = messageBus;
        this.clientEndpoint = clientEndpoint;
        this.hashRing = new HashRing();
        this.pendingRequests = new LinkedHashMap<>();
        messageBus.registerHandler(clientEndpoint, this);
        
        // Initialize hash ring with servers
        for (NetworkEndpoint server : serverEndpoints) {
            hashRing.addServer(server);
        }
    }

    public void addServer(NetworkEndpoint server) {
        hashRing.addServer(server);
    }

    public void removeServer(NetworkEndpoint server) {
        hashRing.removeServer(server);
    }

    public NetworkEndpoint getTargetServer(String key) {
        return hashRing.getServerForKey(key);
    }

    public CompletableFuture<Void> putObject(String key, byte[] data) {
        logger.debug("Sending PUT_OBJECT for key {}", key);
        CompletableFuture<Object> future = new CompletableFuture<>();
        String correlationId = UUID.randomUUID().toString();
        pendingRequests.put(correlationId, future);
        PutObjectMessage message = new PutObjectMessage(key, data, true, correlationId);
        NetworkEndpoint targetServer = getTargetServer(key);
        messageBus.send(message, clientEndpoint, targetServer);
        return future.thenAccept(response -> {
            PutObjectResponseMessage resp = (PutObjectResponseMessage) response;
            if (!resp.isSuccess()) {
                throw new RuntimeException(resp.getErrorMessage());
            }
            logger.debug("PUT_OBJECT successful for key {}", key);
        });
    }

    public CompletableFuture<byte[]> getObject(String key) {
        logger.debug("Sending GET_OBJECT for key {}", key);
        CompletableFuture<Object> future = new CompletableFuture<>();
        String correlationId = UUID.randomUUID().toString();
        pendingRequests.put(correlationId, future);
        GetObjectMessage message = new GetObjectMessage(key, correlationId);
        NetworkEndpoint targetServer = getTargetServer(key);
        messageBus.send(message, clientEndpoint, targetServer);
        return future.thenApply(response -> {
            GetObjectResponseMessage resp = (GetObjectResponseMessage) response;
            if (!resp.isSuccess()) {
                throw new RuntimeException("Failed to retrieve object: " + key);
            }
            logger.debug("GET_OBJECT successful for key {}", key);
            return resp.getData();
        });
    }

    /**
     * Retrieves a specific byte range from an object, similar to S3's range requests.
     * This enables efficient reading of large files by fetching only needed portions.
     * 
     * @param key The object key
     * @param startByte The starting byte position (inclusive, 0-based)
     * @param endByte The ending byte position (inclusive), or -1 for end of file
     * @return CompletableFuture containing the requested byte range
     */
    public CompletableFuture<byte[]> getObjectRange(String key, long startByte, long endByte) {
        logger.debug("Sending GET_OBJECT_RANGE for key {} range {}-{}", key, startByte, endByte);
        CompletableFuture<Object> future = new CompletableFuture<>();
        String correlationId = UUID.randomUUID().toString();
        pendingRequests.put(correlationId, future);
        GetObjectRangeMessage message = new GetObjectRangeMessage(key, startByte, endByte, correlationId);
        NetworkEndpoint targetServer = getTargetServer(key);
        messageBus.send(message, clientEndpoint, targetServer);
        return future.thenApply(response -> {
            GetObjectRangeResponseMessage resp = (GetObjectRangeResponseMessage) response;
            if (!resp.isSuccess()) {
                throw new RuntimeException("Failed to retrieve object range: " + key + 
                    " [" + startByte + "-" + endByte + "]");
            }
            logger.debug("GET_OBJECT_RANGE successful for key {} range {}-{}, returned {} bytes", 
                key, startByte, endByte, resp.getData().length);
            return resp.getData();
        });
    }

    /**
     * Gets the size of an object without downloading the content.
     * Equivalent to HTTP HEAD request.
     * 
     * @param key The object key
     * @return CompletableFuture containing the object size in bytes
     */
    public CompletableFuture<Long> getObjectSize(String key) {
        logger.debug("Sending GET_OBJECT_SIZE for key {}", key);
        CompletableFuture<Object> future = new CompletableFuture<>();
        String correlationId = UUID.randomUUID().toString();
        pendingRequests.put(correlationId, future);
        GetObjectSizeMessage message = new GetObjectSizeMessage(key, correlationId);
        NetworkEndpoint targetServer = getTargetServer(key);
        messageBus.send(message, clientEndpoint, targetServer);
        return future.thenApply(response -> {
            GetObjectSizeResponseMessage resp = (GetObjectSizeResponseMessage) response;
            if (!resp.isSuccess()) {
                throw new RuntimeException("Failed to get object size: " + key);
            }
            logger.debug("GET_OBJECT_SIZE successful for key {}, size: {} bytes", key, resp.getSize());
            return resp.getSize();
        });
    }

    public CompletableFuture<Void> deleteObject(String key) {
        logger.debug("Sending DELETE_OBJECT for key {}", key);
        CompletableFuture<Object> future = new CompletableFuture<>();
        String correlationId = UUID.randomUUID().toString();
        pendingRequests.put(correlationId, future);
        DeleteObjectMessage message = new DeleteObjectMessage(key, correlationId);
        NetworkEndpoint targetServer = getTargetServer(key);
        messageBus.send(message, clientEndpoint, targetServer);
        return future.thenAccept(response -> {
            DeleteObjectResponseMessage resp = (DeleteObjectResponseMessage) response;
            if (!resp.isSuccess()) {
                throw new RuntimeException(resp.getErrorMessage());
            }
            logger.debug("DELETE_OBJECT successful for key {}", key);
        });
    }

    public CompletableFuture<List<String>> listObjects(String prefix) {
        logger.debug("Sending LIST_OBJECTS for prefix {}", prefix);
        List<CompletableFuture<List<String>>> futures = new ArrayList<>();
        
        // Query all servers in parallel
        for (NetworkEndpoint server : hashRing.getServers()) {
            CompletableFuture<Object> future = new CompletableFuture<>();
            // Create unique correlation ID for each request
            String correlationId = UUID.randomUUID().toString();
            pendingRequests.put(correlationId, future);
            ListObjectsMessage message = new ListObjectsMessage(prefix, correlationId);
            messageBus.send(message, clientEndpoint, server);
            
            futures.add(future.thenApply(response -> {
                ListObjectsResponseMessage resp = (ListObjectsResponseMessage) response;
                if (!resp.isSuccess()) {
                    logger.warn("Failed to list objects from server {}: {}", server, resp.getErrorMessage());
                    return Collections.emptyList();
                }
                return resp.getObjects();
            }));
        }
        
        // Combine results from all servers
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<String> allObjects = new ArrayList<>();
                for (CompletableFuture<List<String>> future : futures) {
                    try {
                        // DETERMINISM FIX: Use get() instead of join() since we're already 
                        // inside a thenApply callback and allOf() ensures completion
                        allObjects.addAll(future.get());
                    } catch (Exception e) {
                        logger.warn("Failed to get objects from server: {}", e.getMessage());
                        // Continue with other servers
                    }
                }
                return allObjects.stream().distinct().collect(Collectors.toList());
            });
    }

    @Override
    public void handleMessage(Message message, NetworkEndpoint sender) {
        String correlationId = message.getCorrelationId();
        if (correlationId == null) {
            logger.warn("Received message with null correlationId: {}", message.getType());
            return;
        }

        CompletableFuture<Object> future = pendingRequests.remove(correlationId);
        if (future != null) {
            future.complete(message);
        } else {
            logger.warn("No pending request found for correlationId: {}", correlationId);
        }
    }

    private String createRequestKey(String operation, String key) {
        return operation + ":" + key;
    }
} 