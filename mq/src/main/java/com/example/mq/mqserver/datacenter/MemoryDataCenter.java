package com.example.mq.mqserver.datacenter;

import com.example.mq.common.MqException;
import com.example.mq.mqserver.core.Binding;
import com.example.mq.mqserver.core.Exchange;
import com.example.mq.mqserver.core.MSGQueue;
import com.example.mq.mqserver.core.Message;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created with IntelliJ IDEA.
 * Description: 使用这个类来统一管理内存中的所有数据
 *  该类后续提供的一些方法可能会在多线程环境下被使用，因此要注意线程安全问题
 * User: 12569
 * Date: 2024-03-18
 * Time: 23:32
 */
public class MemoryDataCenter {
    // key 是 exchangeName, value 是 Exchange 对象
    private ConcurrentHashMap<String, Exchange> exchangeMap = new ConcurrentHashMap<>();
    // key 是 queueName, value 是 MSGQueue 对象
    private ConcurrentHashMap<String, MSGQueue> queueMap = new ConcurrentHashMap<>();
    // 第一个 key 是 exchangeName, 第二个 key 是 queueName
    private ConcurrentHashMap<String, ConcurrentHashMap<String, Binding>> bindingsMap = new ConcurrentHashMap<>();
    // key 是 messageId, value 是 Message 对象
    private ConcurrentHashMap<String, Message> messageMap = new ConcurrentHashMap<>();
    // key 是 queueName, value 是一个 Message 的链表
    private ConcurrentHashMap<String, LinkedList<Message>> queueMessageMap = new ConcurrentHashMap<>();
    // 第一个 key 是 queueName, 第二个 key 是 messageId
    private ConcurrentHashMap<String, ConcurrentHashMap<String, Message>> queueMessageWaitAckMap = new ConcurrentHashMap<>();

    public void insertExchange(Exchange exchange) {
        exchangeMap.put(exchange.getName(), exchange);
        System.out.println("[MemoryDataCenter] 新交换机添加成功！exchangeName = " + exchange.getName());
    }

    public Exchange getExchange(String exchangeName) {
        return exchangeMap.get(exchangeName);
    }

    public void deleteExchange(String exchangeName) {
        exchangeMap.remove(exchangeName);
        System.out.println("[MemoryDataCenter] 交换机删除成功! exchangeName=" + exchangeName);
    }

    public void insertQueue(MSGQueue queue) {
        queueMap.put(queue.getName(), queue);
        System.out.println("[MemoryDataCenter] 新队列添加成功! queueName=" + queue.getName());
    }

    public MSGQueue getQueue(String queueName) {
        return queueMap.get(queueName);
    }

    public void deleteQueue(String queueName) {
        queueMap.remove(queueName);
        System.out.println("[MemoryDataCenter] 队列删除成功！queueName = " + queueName);
    }

    public void insertBinding(Binding binding) throws MqException {
        //        ConcurrentHashMap<String, Binding> bindingMap = bindingsMap.get(binding.getExchangeName());
//        if (bindingMap == null) {
//            bindingMap = new ConcurrentHashMap<>();
//            bindingsMap.put(binding.getExchangeName(), bindingMap);
//        }

        // 先使用 exchangeName 查一下对应的哈希表是否存在，不存在就创建一个
        // computeIfAbsent: 根据key查询value，如果value不存在，则会执行一个表达式
        ConcurrentHashMap<String, Binding> bindingMap = bindingsMap.computeIfAbsent(binding.getExchangeName(),
                k -> new ConcurrentHashMap<>());

        // 有两步操作，要加锁
        synchronized (bindingMap) {
            // 再根据 queueName 查一下。如果已经存在，就抛出异常，不存在才能插入
            if (bindingMap.get(binding.getQueueName()) != null) {
                throw new MqException("[MemoryDataCenter] 绑定已经存在! exchangeName = " + binding.getExchangeName() +
                        ", queueName = " + binding.getQueueName());
            }
            bindingMap.put(binding.getQueueName(), binding);
        }
        System.out.println("[MemoryDataCenter] 新绑定添加成功! exchangeName=" + binding.getExchangeName()
                + ", queueName=" + binding.getQueueName());
    }

    /**
     * 获取绑定，版本1：
     * 1. 根据 exchangeName 和 queueName 确定唯一一个 Binding
     * @param exchangeName
     * @param queueName
     * @return
     */
    public Binding getBinding(String exchangeName, String queueName) {
        ConcurrentHashMap<String, Binding> bindingMap = bindingsMap.get(exchangeName);
        if (bindingMap == null) {
            return null;
        }
        return bindingMap.get(queueName);
    }

    /**
     * 获取绑定，版本2
     * 2. 根据 exchangeName 获取到所有的 Binding
     * @param exchangeName
     * @return
     */
    public ConcurrentHashMap<String, Binding> getBindings(String exchangeName) {
        return bindingsMap.get(exchangeName);
    }

    public void deleteBinding(Binding binding) throws MqException {
        ConcurrentHashMap<String, Binding> bindingMap = bindingsMap.get(binding.getExchangeName());
        if (bindingMap == null) {
            // 该交换机没有绑定任何队列. 报错.
            throw new MqException("[MemoryDataCenter] 绑定不存在! exchangeName = " + binding.getExchangeName()
                    + ", queueName = " + binding.getQueueName());
        }
        bindingMap.remove(binding.getQueueName());
        System.out.println("[MemoryDataCenter] 绑定删除成功! exchangeName=" + binding.getExchangeName()
                + ", queueName=" + binding.getQueueName());
    }

    /**
     * 添加消息
     * @param message
     */
    public void addMessage(Message message) {
        messageMap.put(message.getMessageId(), message);
        System.out.println("[MemoryDataCenter] 新消息添加成功！messageId = " + message.getMessageId());
    }


    /**
     * 根据 id 查询消息
     * @param messageId
     * @return
     */
    public Message getMessage(String messageId) {
        return messageMap.get(messageId);
    }

    /**
     * 根据 id 删除消息
     * @param messageId
     */
    public void removeMessage(String messageId) {
        messageMap.remove(messageId);
        System.out.println("[MemoryDataCenter] 消息被移除！messageId = " + messageId);
    }

    /**
     * 发送消息到指定队列
     * @param queue
     * @param message
     */
    public void sendMessage(MSGQueue queue, Message message) {
        // 把消息放到对应的队列数据结构中
        // 先根据队列的名字, 找到该队列对应的消息链表
        LinkedList<Message> messages = queueMessageMap.computeIfAbsent(queue.getName(), k -> new LinkedList<>());
        // 再把数据加到 messages 里面
        synchronized (messages) {
            messages.add(message);
        }
        // 在这里把该消息也往消息中心中插入一下。假设如果 message 已经在消息中心存在, 重复插入也没关系.
        // 相同 messageId, 对应的 message 的内容一定是一样的(服务器代码不会对 Message 内容做修改 basicProperties 和 body)
        addMessage(message);
        System.out.println("[MemoryDataCenter] 消息被投递到队列中! messageId=" + message.getMessageId());
    }

    // 从队列中取消息
    public Message pollMessage(String queueName) {
        // 根据队列名查找一下对应的队列的消息链表
        LinkedList<Message> messages = queueMessageMap.get(queueName);
        if (messages == null) {
            return null;
        }
        synchronized (messages) {
            // 如果没找到, 说明队列中没有任何消息.
            if (messages.size() == 0) {
                return null;
            }
            // 链表中有元素, 就进行头删
            Message currentMessage = messages.remove(0);
            System.out.println("[MemoryDataCenter] 消息从队列中取出! messageId=" + currentMessage.getMessageId());
            return currentMessage;
        }
    }

    // 获取指定队列中消息的个数
    public int getMessageCount(String queueName) {
        LinkedList<Message> messages = queueMessageMap.get(queueName);
        if (messages == null) {
            // 队列中没有消息
            return 0;
        }
        synchronized (messages) {
            return messages.size();
        }
    }
}
