Zookeeper��Ϣ���л���ʽ����:
�ͻ���ʹ��Packet��������Ϣ.Packet����:
    �����ͷ(RequestHeader)���������(Record�Ĳ�ͬʵ�֣�������xxxRequest);
    Ӧ���ͷ(ReplyHeader)��Ӧ�����(Record�Ĳ�ͬʵ�֣�������xxxResponse). 

�����������ͨ��ʱ�������Լ�ʵ�����л���ʽ�����л������ͷ�Լ�������壬���ҷ����л�Ӧ���ͷ�Լ�Ӧ����塣

RequestHeader
    xid���ͻ������ɵģ����ڸ��ٰ�������������ʽ���ɵģ���Ҫ����������Ӧʱ���أ�
    type����ʾ�����Ĳ������ͣ���ZooDefs.OpCode������һ������ֵ���ֱ����"notification", "create","delete", "exists","getData",     "setData", "getACL","setACL","getChildren", "getChildren2","getMaxChildren", "setMaxChildren", "ping"��
          getChildren��getChildren2���������������Ƿ񷵻�Statͳ�����ݣ�����ͳ������Stat�Ĳ�����getChildren2��


ReplyHeader
    ǰ��4���ֽ���xid, �м�8���ֽ���zxid,��ʾ����id, ���4���ֽڱ�ʾerr��Ϣ,����ο�Code��.





client---->server
(����ͷ�������塢��Ӧͷ����Ӧ��)====��װ====>Packet====���л�====>ByteArrayOutputStream========>ByteBuffer��������SocketChannel��д������
��SocketChannel��ȡ���ݵõ�ByteBuffer========>ByteBufferInputStream====�����л�====>(����ͷ�������塢��Ӧͷ����Ӧ��)
                                     ========>Request





/**
 *ClientCnxn����ά��������List����
 */
//outGoingQueue��Ϊ�ͻ��������Ͷ���, ά���ͻ����ύ��Packet
private final LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();

//���ͻ��������˷������ݣ������ҵ�������򽫴�����Packet��ӵ�Pending���У�������������󣬷��������
//����������server����client�˷������ݺ�client�˵���readResponse()������ȡ��Ӧ����ʱ�����Դ�pendingQueue������ȡ��һ��ʼ�ŵ�Packet����
//�Ӷ��ı�Packet�����finished����Ϊtrue������������������������߳�(�ͻ��˵���zookeeper api���߳�)
private final LinkedList<Packet> pendingQueue = new LinkedList<Packet>();
