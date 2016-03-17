Zookeeper消息序列化格式解析:
客户端使用Packet来管理消息.Packet管理:
    请求包头(RequestHeader)和请求包体(Record的不同实现，类似于xxxRequest);
    应答包头(ReplyHeader)和应答包体(Record的不同实现，类似于xxxResponse). 

与服务器进行通信时，依靠自己实现序列化方式来序列化请求包头以及请求包体，并且反序列化应答包头以及应答包体。

RequestHeader
    xid：客户端生成的，用于跟踪包，采用自增方式生成的，需要服务器在响应时返回；
    type：表示本包的操作类型：即ZooDefs.OpCode，它是一个整数值，分别代表"notification", "create","delete", "exists","getData",     "setData", "getACL","setACL","getChildren", "getChildren2","getMaxChildren", "setMaxChildren", "ping"。
          getChildren和getChildren2操作的区别在于是否返回Stat统计数据，返回统计数据Stat的操作是getChildren2。


ReplyHeader
    前面4个字节是xid, 中间8个字节是zxid,表示事务id, 最后4个字节表示err信息,具体参考Code类.





client---->server
(请求头、请求体、响应头、响应体)====组装====>Packet====序列化====>ByteArrayOutputStream========>ByteBuffer，用来向SocketChannel中写入数据
从SocketChannel读取数据得到ByteBuffer========>ByteBufferInputStream====反序列化====>(请求头、请求体、响应头、响应体)
                                     ========>Request





/**
 *ClientCnxn类中维护这两个List集合
 */
//outGoingQueue作为客户端请求发送队列, 维护客户端提交的Packet
private final LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();

//当客户端向服务端发送数据，如果是业务请求，则将创建的Packet添加到Pending队列，如果是其他请求，发完就扔了
//这样方便在server端向client端发送数据后，client端调用readResponse()方法读取响应数据时，可以从pendingQueue队列中取出一开始放的Packet对象，
//从而改变Packet对象的finished属性为true，唤醒在这个条件上阻塞的线程(客户端调用zookeeper api的线程)
private final LinkedList<Packet> pendingQueue = new LinkedList<Packet>();
