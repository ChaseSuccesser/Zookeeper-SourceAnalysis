/**
Zookeeper�ڵ�״̬��������
StatPersisted�ࣺ
*/
    czxid. �ڵ㴴��ʱ��zxid.
    mzxid. �ڵ�����һ�θ��·���ʱ��zxid.
    ctime. �ڵ㴴��ʱ��ʱ���.
    mtime. �ڵ�����һ�θ��·���ʱ��ʱ���.
    version. �ڵ����ݵĸ��´���.
    cversion. ���ӽڵ�ĸ��´���.
    aversion. �ڵ�ACL(��Ȩ��Ϣ)�ĸ��´���.
    ephemeralOwner. ����ýڵ�Ϊephemeral�ڵ�, ephemeralOwnerֵ��ʾ��ýڵ�󶨵�sessionId. ����ýڵ㲻��ephemeral�ڵ�, ephemeralOwnerֵΪ0. ����ʲô��ephemeral�ڵ�, �뿴����Ľ���.
    pzxid

ZooKeeper״̬��ÿһ�θı�, ����Ӧ��һ������������id, ��id��Ϊzxid. ���е�������,��
���zxid1С��zxid2, ��ôzxid1�϶�����zxid2����. ��������ڵ�, ���߸�������ڵ������, ����ɾ������ڵ�, ���ᵼ��Zookeeper״̬�����ı�, �Ӷ�����zxid��ֵ����.



/**
ChangeRecord�ࣺ
*/
    zxid���ڵ��zxid
    path���ڵ��·��
    stat��StatPersisted����
    childCount���ӽڵ����
    acl���ڵ����Ȩ����Ϣ

ZookeeperServer���У���һ��Map���ϣ�ά����ÿһ��·����Ӧ��ChangeRecord����
HashMap<String, ChangeRecord> outstandingChangesForPath = new HashMap<String, ChangeRecord>(); //key���ڵ��·���� value���ڵ��ChangeRecord����




/**
Zookeeper�ڵ���������
DataNode�ࣺ
*/
    parent���ڵ�ĸ��ڵ�
    children���ڵ���ӽڵ㼯��
    data���ڵ������
    acl���ڵ�ķ���Ȩ��
    stat���ڵ��״̬

/**
DataTree���У���һ��Map���ϣ�ά����ÿһ��·����Ӧ�Ľڵ㣺
*/
ConcurrentHashMap<String, DataNode> nodes = new ConcurrentHashMap<String, DataNode>();
