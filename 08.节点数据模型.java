/**
Zookeeper节点状态数据类型
StatPersisted类：
*/
    czxid. 节点创建时的zxid.
    mzxid. 节点最新一次更新发生时的zxid.
    ctime. 节点创建时的时间戳.
    mtime. 节点最新一次更新发生时的时间戳.
    version. 节点数据的更新次数.
    cversion. 其子节点的更新次数.
    aversion. 节点ACL(授权信息)的更新次数.
    ephemeralOwner. 如果该节点为ephemeral节点, ephemeralOwner值表示与该节点绑定的sessionId. 如果该节点不是ephemeral节点, ephemeralOwner值为0. 至于什么是ephemeral节点, 请看后面的讲述.
    pzxid

ZooKeeper状态的每一次改变, 都对应着一个递增的事务id, 该id称为zxid. 具有递增性质,。
如果zxid1小于zxid2, 那么zxid1肯定先于zxid2发生. 创建任意节点, 或者更新任意节点的数据, 或者删除任意节点, 都会导致Zookeeper状态发生改变, 从而导致zxid的值增加.



/**
ChangeRecord类：
*/
    zxid：节点的zxid
    path：节点的路径
    stat：StatPersisted对象
    childCount：子节点个数
    acl：节点访问权限信息

ZookeeperServer类中，有一个Map集合，维护着每一个路径对应的ChangeRecord对象
HashMap<String, ChangeRecord> outstandingChangesForPath = new HashMap<String, ChangeRecord>(); //key：节点的路径； value：节点的ChangeRecord对象




/**
Zookeeper节点数据类型
DataNode类：
*/
    parent：节点的父节点
    children：节点的子节点集合
    data：节点的数据
    acl：节点的访问权限
    stat：节点的状态

/**
DataTree类中，有一个Map集合，维护着每一个路径对应的节点：
*/
ConcurrentHashMap<String, DataNode> nodes = new ConcurrentHashMap<String, DataNode>();
