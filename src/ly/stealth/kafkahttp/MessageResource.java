package ly.stealth.kafkahttp;

import com.google.common.base.Strings;
import com.yammer.metrics.annotation.Timed;
import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerTimeoutException;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.javaapi.producer.Producer;
import kafka.message.MessageAndMetadata;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.util.*;

@Path("/message")
@Produces(MediaType.APPLICATION_JSON)
public class MessageResource {
    private KafkaConfiguration configuration;

    public MessageResource(KafkaConfiguration configuration) {
        this.configuration = configuration;
    }

    @POST
    @Timed
    public Response produce(
            @QueryParam("topic") String topic,
            @QueryParam("async") Boolean async,
            @QueryParam("key") List<String> keys,
            @QueryParam("message") List<String> messages
    ) {
        List<String> errors = new ArrayList<>();
        if (Strings.isNullOrEmpty(topic)) errors.add("Undefined topic");

        if (keys.isEmpty()) errors.add("Undefined key");
        if (messages.isEmpty()) errors.add("Undefined message");
        if (keys.size() != messages.size()) errors.add("Messages count != keys count");

        if (!errors.isEmpty())
            return Response.status(400)
                    .entity(errors)
                    .build();

        assert keys != null;
        assert messages != null;

        List<KeyedMessage<String, String>> keyedMessages = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String message = messages.get(i);
            keyedMessages.add(new KeyedMessage<>(topic, key, message));
        }

        ProducerConfig config = new ProducerConfig(configuration.producer.asProperties(async));
        Producer<String, String> producer = new Producer<>(config);

        try { producer.send(keyedMessages); }
        finally { producer.close(); }

        return Response.ok().build();
    }

    @GET
    @Timed
    public Response consume(
            @QueryParam("topic") String topic,
            @QueryParam("timeout") Integer timeout
    ) {
        if (Strings.isNullOrEmpty(topic))
            return Response.status(400)
                    .entity(new String[]{"Undefined topic"})
                    .build();

        Properties props = configuration.consumer.asProperties(timeout);
        ConsumerConfig config = new ConsumerConfig(props);
        ConsumerConnector connector = Consumer.createJavaConsumerConnector(config);

        Map<String, Integer> streamCounts = Collections.singletonMap(topic, 1);
        Map<String, List<KafkaStream<byte[], byte[]>>> streams = connector.createMessageStreams(streamCounts);
        KafkaStream<byte[], byte[]> stream = streams.get(topic).get(0);

        List<Message> messages = new ArrayList<>();
        try {
            for (MessageAndMetadata<byte[], byte[]> messageAndMetadata : stream)
                messages.add(new Message(messageAndMetadata));
        } catch (ConsumerTimeoutException ignore) {
        } finally {
            connector.commitOffsets();
            connector.shutdown();
        }

        return Response.ok(messages).build();
    }

    public static class Message {
        public String topic;

        public String key;
        public String message;

        public int partition;
        public long offset;

        public Message(MessageAndMetadata<byte[], byte[]> message) {
            this.topic = message.topic();

            try {
                this.key = new String(message.key(), "UTF-8");
                this.message = new String(message.message(), "UTF-8");
            } catch (UnsupportedEncodingException impossible) { /* ignore */ }

            this.partition = message.partition();
            this.offset = message.offset();
        }
    }
}
