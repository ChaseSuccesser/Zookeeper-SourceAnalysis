    调用Zookeeper构造方法：
    public ZooKeeper(String connectString, int sessionTimeout, Watcher watcher, boolean canBeReadOnly) throws IOException {
        watchManager.defaultWatcher = watcher;

        ConnectStringParser connectStringParser = new ConnectStringParser(connectString);
        HostProvider hostProvider = new StaticHostProvider(connectStringParser.getServerAddresses());
        cnxn = new ClientCnxn(connectStringParser.getChrootPath(), 
                       hostProvider, sessionTimeout, this, watchManager, getClientCnxnSocket(), canBeReadOnly);
        cnxn.start();
    }
    // 创建ClientCnxnSocketNIO类的实例，交给SendThread类
    // ClientCnxnSocketNIO类用来与服务端进行通信
    private static ClientCnxnSocket getClientCnxnSocket() throws IOException {
        String clientCnxnSocketName = System .getProperty(ZOOKEEPER_CLIENT_CNXN_SOCKET);
        if (clientCnxnSocketName == null) {
            // 创建实例
            clientCnxnSocketName = ClientCnxnSocketNIO.class.getName();
        }
        try {
            return (ClientCnxnSocket) Class.forName(clientCnxnSocketName) .newInstance();
        } catch (Exception e) {
            IOException ioe = new IOException("Couldn't instantiate " + clientCnxnSocketName);
            ioe.initCause(e);
            throw ioe;
        }
    }

    //创建zk客户端，初始化zk客户端的参数
    // sessionTimeout:客户端指定的，sessionId=0, sessionPasswd=new byte[16]
    public ClientCnxn(String chrootPath, HostProvider hostProvider, int sessionTimeout, ZooKeeper zooKeeper,
            ClientWatchManager watcher, ClientCnxnSocket clientCnxnSocket,
            long sessionId, byte[] sessionPasswd, boolean canBeReadOnly) {
        this.zooKeeper = zooKeeper;
        this.watcher = watcher;
        this.sessionId = sessionId;
        this.sessionPasswd = sessionPasswd;
        //客户端设置的超时时间
        this.sessionTimeout = sessionTimeout;
        this.hostProvider = hostProvider;
        this.chrootPath = chrootPath;
        
        //连接超时
        connectTimeout = sessionTimeout / hostProvider.size();
        //读超时
        readTimeout = sessionTimeout * 2 / 3;
        readOnly = canBeReadOnly;

        sendThread = new SendThread(clientCnxnSocket);
        eventThread = new EventThread();
    }




    //初始化线程的名字，设置Zookeeper状态为CONNECTING，设置线程为守护线程
    SendThread(ClientCnxnSocket clientCnxnSocket) {
        super(makeThreadName("-SendThread()"));
        state = States.CONNECTING;
        /**
         *SendThread通过SocketChannel(ClientCnxnSocketNIO)与服务端通信
         */
        this.clientCnxnSocket = clientCnxnSocket;
        setUncaughtExceptionHandler(uncaughtExceptionHandler);
        setDaemon(true);
    }

    EventThread() {
        super(makeThreadName("-EventThread"));
        setUncaughtExceptionHandler(uncaughtExceptionHandler);
        setDaemon(true);
    }

    ClientCnxn：    
    public void start() {
        sendThread.start();
        eventThread.start();
    }


