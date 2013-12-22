package org.dna.mqtt.moquette.messaging.spi.impl;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.dna.mqtt.moquette.messaging.spi.IStorageService;
import org.dna.mqtt.moquette.messaging.spi.impl.events.PublishEvent;
import org.dna.mqtt.moquette.messaging.spi.impl.subscriptions.Subscription;
import org.dna.mqtt.moquette.messaging.spi.impl.subscriptions.SubscriptionsStore;
import org.dna.mqtt.moquette.proto.messages.AbstractMessage;
import org.dna.mqtt.moquette.proto.messages.AbstractMessage.QOSType;
import org.dna.mqtt.moquette.proto.messages.ConnAckMessage;
import org.dna.mqtt.moquette.proto.messages.ConnectMessage;
import org.dna.mqtt.moquette.proto.messages.PublishMessage;
import org.dna.mqtt.moquette.proto.messages.SubAckMessage;
import org.dna.mqtt.moquette.proto.messages.SubscribeMessage;
import org.dna.mqtt.moquette.server.ServerChannel;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author andrea
 */
public class ProtocolProcessorTest {
    final static String FAKE_CLIENT_ID = "FAKE_123";
    final static String FAKE_PUBLISHER_ID = "Publisher";
    final static String FAKE_TOPIC = "/news";
    
    ServerChannel m_session;
    byte m_returnCode;
    ConnectMessage connMsg;
    ProtocolProcessor m_processor;
    
    IStorageService m_storageService;
    SubscriptionsStore subscriptions;
    AbstractMessage m_receivedMessage;
    
    class DummyChannel implements ServerChannel {
        
        private Map<Object, Object> m_attributes = new HashMap<Object, Object>();

        public Object getAttribute(Object key) {
            return m_attributes.get(key);
        }

        public void setAttribute(Object key, Object value) {
            m_attributes.put(key, value);
        }

        public void setIdleTime(int idleTime) {
        }

        public void close(boolean immediately) {
            
        }

        public void write(Object value) {
            try {
                m_receivedMessage = (AbstractMessage) value;
                if (m_receivedMessage instanceof ConnAckMessage) {
                    ConnAckMessage buf = (ConnAckMessage) m_receivedMessage;
                    m_returnCode = buf.getReturnCode();
                }
            } catch (Exception ex) {
                throw new AssertionError("Wrong return code");
            }
        }
    } 
    
    
    @Before
    public void setUp() throws InterruptedException {
        connMsg = new ConnectMessage();
        connMsg.setProcotolVersion((byte) 0x03);

        m_session = new DummyChannel();

        //sleep to let the messaging batch processor to process the initEvent
        Thread.sleep(300);
        
        m_storageService = new MemoryStorageService();
        //m_storageService.initStore();

        subscriptions = new SubscriptionsStore();
        subscriptions.init(m_storageService);
        m_processor = new ProtocolProcessor();
        m_processor.init(subscriptions, m_storageService);
    }
    
    @Test
    public void testHandleConnect_BadProtocol() {
        connMsg.setProcotolVersion((byte) 0x02);

        //Exercise
        m_processor.processConnect(m_session, connMsg);

        //Verify
        assertEquals(ConnAckMessage.UNNACEPTABLE_PROTOCOL_VERSION, m_returnCode);
    }
    
    @Test
    public void testConnect_badClientID() {
        connMsg.setClientID("extremely_long_clientID_greater_than_23");

        //Exercise
        m_processor.processConnect(m_session, connMsg);

        //Verify
        assertEquals(ConnAckMessage.IDENTIFIER_REJECTED, m_returnCode);
    }

    @Test
    public void testWill() {
        connMsg.setClientID("123");
        connMsg.setWillFlag(true);
        connMsg.setWillTopic("topic");
        connMsg.setWillMessage("Topic message");
        

        //Exercise
        //m_handler.setMessaging(mockedMessaging);
        m_processor.processConnect(m_session, connMsg);

        //Verify
        assertEquals(ConnAckMessage.CONNECTION_ACCEPTED, m_returnCode);
        //TODO verify the call
        /*verify(mockedMessaging).publish(eq("topic"), eq("Topic message".getBytes()),
                any(AbstractMessage.QOSType.class), anyBoolean(), eq("123"), any(IoSession.class));*/
    }
    
    @Test
    public void testPublish() throws InterruptedException {
        final Subscription subscription = new Subscription(FAKE_CLIENT_ID, 
                FAKE_TOPIC, AbstractMessage.QOSType.MOST_ONE, true);

        //subscriptions.matches(topic) redefine the method to return true
        SubscriptionsStore subs = new SubscriptionsStore() {
            @Override
            public List<Subscription> matches(String topic) {
                if (topic.equals(FAKE_TOPIC)) {
                    return Arrays.asList(subscription);
                } else {
                    throw new IllegalArgumentException("Expected " + FAKE_TOPIC + " buf found " + topic);
                }
            }


        };
        
        //simulate a connect that register a clientID to an IoSession
        subs.init(m_storageService);
        m_processor.init(subs, m_storageService);
        ConnectMessage connectMessage = new ConnectMessage();
        connectMessage.setProcotolVersion((byte)3);
        connectMessage.setClientID(FAKE_CLIENT_ID);
        connectMessage.setCleanSession(subscription.isCleanSession());
        m_processor.processConnect(m_session, connectMessage);
        
        
        //Exercise
        ByteBuffer buffer = ByteBuffer.allocate(5).put("Hello".getBytes());
        PublishEvent pubEvt = new PublishEvent(FAKE_TOPIC, AbstractMessage.QOSType.MOST_ONE, buffer, false, "FakeCLI", null);
        m_processor.processPublish(pubEvt);

        //Verify
        assertNotNull(m_receivedMessage);
        //TODO check received message attributes
    }
    
    @Test
    public void testSubscribe() {
        //Exercise
        SubscribeMessage msg = new SubscribeMessage();
        msg.addSubscription(new SubscribeMessage.Couple((byte)AbstractMessage.QOSType.MOST_ONE.ordinal(), FAKE_TOPIC));
        m_processor.processSubscribe(m_session, msg, FAKE_CLIENT_ID, false);

        //Verify
        assertTrue(m_receivedMessage instanceof SubAckMessage);
        Subscription expectedSubscription = new Subscription(FAKE_CLIENT_ID, FAKE_TOPIC, AbstractMessage.QOSType.MOST_ONE, false);
        assertTrue(subscriptions.contains(expectedSubscription));
    }
    
    @Test
    public void testDoubleSubscribe() {
        SubscribeMessage msg = new SubscribeMessage();
        msg.addSubscription(new SubscribeMessage.Couple((byte)AbstractMessage.QOSType.MOST_ONE.ordinal(), FAKE_TOPIC));
        
        subscriptions.clearAllSubscriptions();
        assertEquals(0, subscriptions.size());
        
        m_processor.processSubscribe(m_session, msg, FAKE_CLIENT_ID, false);
                
        //Exercise
        m_processor.processSubscribe(m_session, msg, FAKE_CLIENT_ID, false);

        //Verify
        assertEquals(1, subscriptions.size());
        Subscription expectedSubscription = new Subscription(FAKE_CLIENT_ID, FAKE_TOPIC, AbstractMessage.QOSType.MOST_ONE, false);
        assertTrue(subscriptions.contains(expectedSubscription));
    }

    
    @Test
    public void testPublishOfRetainedMessage_afterNewSubscription() throws Exception {
        /*ServerChannel */m_session = new DummyChannel() {
            @Override
            public void write(Object value) {
                try {
                    System.out.println("filterReceived class " + value.getClass().getName());
                    if (value instanceof PublishMessage) {
                        m_receivedMessage = (AbstractMessage) value;
                    }
                    
                    if (m_receivedMessage instanceof ConnAckMessage) {
                        ConnAckMessage buf = (ConnAckMessage) m_receivedMessage;
                        m_returnCode = buf.getReturnCode();
                    }
                } catch (Exception ex) {
                    throw new AssertionError("Wrong return code");
                }
            }   
        };
        
        //simulate a connect that register a clientID to an IoSession
        final Subscription subscription = new Subscription(FAKE_PUBLISHER_ID, 
                FAKE_TOPIC, AbstractMessage.QOSType.MOST_ONE, true);

        //subscriptions.matches(topic) redefine the method to return true
        SubscriptionsStore subs = new SubscriptionsStore() {
            @Override
            public List<Subscription> matches(String topic) {
                if (topic.equals(FAKE_TOPIC)) {
                    return Arrays.asList(subscription);
                } else {
                    throw new IllegalArgumentException("Expected " + FAKE_TOPIC + " buf found " + topic);
                }
            }
        };
        subs.init(new MemoryStorageService());
        
        //simulate a connect that register a clientID to an IoSession
        m_processor.init(subs, m_storageService);
        ConnectMessage connectMessage = new ConnectMessage();
        connectMessage.setClientID(FAKE_PUBLISHER_ID);
        connectMessage.setProcotolVersion((byte)3);
        connectMessage.setCleanSession(subscription.isCleanSession());
        m_processor.processConnect(m_session, connectMessage);
        ByteBuffer buffer = ByteBuffer.allocate(5).put("Hello".getBytes());
        PublishEvent pubEvt = new PublishEvent(FAKE_TOPIC, AbstractMessage.QOSType.MOST_ONE, buffer, true, "Publisher", null);
        m_processor.processPublish(pubEvt);
        
        //Exercise
        SubscribeMessage msg = new SubscribeMessage();
        msg.addSubscription(new SubscribeMessage.Couple((byte)QOSType.MOST_ONE.ordinal(), "#"));
        m_processor.processSubscribe(m_session, msg, FAKE_PUBLISHER_ID, false);
        
        //Verify
        assertNotNull(m_receivedMessage); 
        assertTrue(m_receivedMessage instanceof PublishMessage);
        PublishMessage pubMessage = (PublishMessage) m_receivedMessage;
        assertEquals(FAKE_TOPIC, pubMessage.getTopicName());
    }
    
    @Test
    public void processPublishNoPublishToInactiveSubscriptions() {
        SubscriptionsStore mockedSubscriptions = mock(SubscriptionsStore.class);
        Subscription inactiveSub = new Subscription("Subscriber", "/topic", QOSType.LEAST_ONE, false); 
        inactiveSub.setActive(false);
        List<Subscription> inactiveSubscriptions = Arrays.asList(inactiveSub);
        when(mockedSubscriptions.matches(eq("/topic"))).thenReturn(inactiveSubscriptions);
        m_processor = new ProtocolProcessor();
        m_processor.init(mockedSubscriptions, m_storageService);
        
        //Exercise
        ByteBuffer buffer = ByteBuffer.allocate(5).put("Hello".getBytes());
        PublishEvent pubEvt = new PublishEvent("/topic", AbstractMessage.QOSType.MOST_ONE, buffer, true, "Publisher", null);
        m_processor.processPublish(pubEvt);

        //Verify no message is received
        assertNull(m_receivedMessage); 
    }
    
}
