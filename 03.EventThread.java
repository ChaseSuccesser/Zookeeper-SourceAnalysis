Watcher类有两类枚举：
    EventType(None、NodeCreate、NodeDelete、NodeDataChanged、NodeChildrenChanged)；
    KeeperState(Disconnected、SyncConnected、AuthFailed、Expired)

WatchedEvent类有三个属性：
    KeeperState：Zookeeper状态
    EventType：事件类型
    path：节点路径

WatcherSetEventPair类包含两个属性：
    一组watcher集合
    一个WatchedEvent实例

    多个Watcher可能会监控同一个节点的同一个事件类型。当这个节点的此事件发生了，每个watcher就开始调用process()方法做相应的处理。



    //--------------EventThread线程主要的工作：当某节点发生了某事件，监控此节点的Watcher调用process()进行相应的处理--------------------


    /**
     * 将一个Watcher添加到监控clientPath的指定Watcher集合中
     *
     * 根据事件类型，确定是哪种Map集合；
     * 根据节点路径，确定Watcher集合；
     * 然后将Watcher添加进Watcher集合 
     */
    abstract class WatchRegistration {
        private Watcher watcher;
        private String clientPath;
        public WatchRegistration(Watcher watcher, String clientPath){
            this.watcher = watcher;
            this.clientPath = clientPath;
        }

        abstract protected Map<String, Set<Watcher>> getWatches(int rc);

        // 此方法在finishPacket(Packet p)中被调用
        public void register(int rc) {
            if (shouldAddWatch(rc)) {
                Map<String, Set<Watcher>> watches = getWatches(rc);
                synchronized(watches) {
                    Set<Watcher> watchers = watches.get(clientPath);
                    if (watchers == null) {
                        watchers = new HashSet<Watcher>();
                        watches.put(clientPath, watchers);
                    }
                    watchers.add(watcher);
                }
            }
        }
        // Determine whether the watch should be added based on return code.
        // @param rc the result code of the operation that attempted to add the watch on the node
        protected boolean shouldAddWatch(int rc) {
            return rc == 0;
        }
    }
    // WatchRegistration抽象类的几个实现
    class ExistsWatchRegistration extends WatchRegistration {
        public ExistsWatchRegistration(Watcher watcher, String clientPath) {
            super(watcher, clientPath);
        }
        @Override
        protected Map<String, Set<Watcher>> getWatches(int rc) {
            return rc == 0 ?  watchManager.dataWatches : watchManager.existWatches;
        }
        @Override
        protected boolean shouldAddWatch(int rc) {
            return rc == 0 || rc == KeeperException.Code.NONODE.intValue();
        }
    }
    class DataWatchRegistration extends WatchRegistration {
        public DataWatchRegistration(Watcher watcher, String clientPath) {
            super(watcher, clientPath);
        }
        @Override
        protected Map<String, Set<Watcher>> getWatches(int rc) {
            return watchManager.dataWatches;
        }
    }
    class ChildWatchRegistration extends WatchRegistration {
        public ChildWatchRegistration(Watcher watcher, String clientPath) {
            super(watcher, clientPath);
        }
        @Override
        protected Map<String, Set<Watcher>> getWatches(int rc) {
            return watchManager.childWatches;
        }
    }

    /**
     * 根据节点发生的事件，返回与该事件相关联的Watcher集合
     *
     * 根据事件类型，确定是哪种Map集合；
     * 根据节点路径，确定Watcher集合；
     * 然后返回Watcher集合
     */
    private static class ZKWatchManager implements ClientWatchManager {
        // 保存着每个节点和监控此节点的Watcher集合
        // 有三种类型的集合，分别存储每个节点不同事件类型的Watcher
        private final Map<String, Set<Watcher>> dataWatches = new HashMap<String, Set<Watcher>>();
        private final Map<String, Set<Watcher>> existWatches = new HashMap<String, Set<Watcher>>();
        private final Map<String, Set<Watcher>> childWatches = new HashMap<String, Set<Watcher>>(); 
        private volatile Watcher defaultWatcher;

        final private void addTo(Set<Watcher> from, Set<Watcher> to) {
            if (from != null) {
                to.addAll(from);
            }
        }

        @Override
        public Set<Watcher> materialize(Watcher.Event.KeeperState state,
                                        Watcher.Event.EventType type,
                                        String clientPath) {
            Set<Watcher> result = new HashSet<Watcher>();

            switch (type) {
            case None:
                // 如果节点发生的事件类型为NONE，则返回所有的Watcher
                result.add(defaultWatcher);
                boolean clear = ClientCnxn.getDisableAutoResetWatch() &&
                        state != Watcher.Event.KeeperState.SyncConnected;

                synchronized(dataWatches) {
                    for(Set<Watcher> ws: dataWatches.values()) {
                        result.addAll(ws);
                    }
                    if (clear) {
                        dataWatches.clear();
                    }
                }

                synchronized(existWatches) {
                    for(Set<Watcher> ws: existWatches.values()) {
                        result.addAll(ws);
                    }
                    if (clear) {
                        existWatches.clear();
                    }
                }

                synchronized(childWatches) {
                    for(Set<Watcher> ws: childWatches.values()) {
                        result.addAll(ws);
                    }
                    if (clear) {
                        childWatches.clear();
                    }
                }

                return result;
            case NodeDataChanged:
            case NodeCreated:
                synchronized (dataWatches) {
                    addTo(dataWatches.remove(clientPath), result);
                }
                synchronized (existWatches) {
                    addTo(existWatches.remove(clientPath), result);
                }
                break;
            case NodeChildrenChanged:
                synchronized (childWatches) {
                    addTo(childWatches.remove(clientPath), result);
                }
                break;
            case NodeDeleted:
                synchronized (dataWatches) {
                    addTo(dataWatches.remove(clientPath), result);
                }
                synchronized (existWatches) {
                    Set<Watcher> list = existWatches.remove(clientPath);
                    if (list != null) {
                        addTo(existWatches.remove(clientPath), result);
                        LOG.warn("We are triggering an exists watch for delete! Shouldn't happen!");
                    }
                }
                synchronized (childWatches) {
                    addTo(childWatches.remove(clientPath), result);
                }
                break;
            default:
                String msg = "Unhandled watch event type " + type
                    + " with state " + state + " on path " + clientPath;
                LOG.error(msg);
                throw new RuntimeException(msg);
            }

            return result;
        }
    }


    public void queueEvent(WatchedEvent event) {
        if (event.getType() == EventType.None && sessionState == event.getState()) {
            return;
        }
        sessionState = event.getState();

        WatcherSetEventPair pair = new WatcherSetEventPair(watcher.materialize(event.getState(), event.getType(),event.getPath()), event);
        waitingEvents.add(pair);
    }



    //正常情况下，EventThread主要做下面代码的1、2步：取事件、处理事件。
    //
    //当ClientCnxn调用disConnect()断开连接 或者 发生SessionTimeout时，会调用EventThread的queueEventOfDeath()方法，将eventOfDeath对象放进waitingEvents队列中去
    //这样在EventThread进行while循环时，会从waitingEvents队列中取出eventOfDeath对象，从而将wasKilled置为true，
    //然后清空waitingEvents，EventThread退出循环，线程死亡。
    public void run() {
       try {
          isRunning = true;
          while (true) {
             // LinkedBlockingQueue<Object> waitingEvents = new LinkedBlockingQueue<Object>();
             // 从队列中取出一个WatcherSetEventPair对象去处理。如果队列中为空，就一直阻塞。
             Object event = waitingEvents.take();  //1
             if (event == eventOfDeath) {
                wasKilled = true;
             } else {
                processEvent(event);               //2
             }
             if (wasKilled)
                synchronized (waitingEvents) {
                   if (waitingEvents.isEmpty()) {
                      isRunning = false;
                      break;
                   }
                }
          }
       } catch (InterruptedException e) {
          LOG.error("Event thread exiting due to interruption", e);
       }
       LOG.info("EventThread shut down");
    }


       private void processEvent(Object event) {
          try {
              if (event instanceof WatcherSetEventPair) {
                  WatcherSetEventPair pair = (WatcherSetEventPair) event;
                  for (Watcher watcher : pair.watchers) {
                      try {
                          watcher.process(pair.event);   // each watcher will process the event
                      } catch (Throwable t) {
                          LOG.error("Error while calling watcher ", t);
                      }
                  }
              } else {
                  Packet p = (Packet) event;
                  int rc = 0;
                  String clientPath = p.clientPath;
                  if (p.replyHeader.getErr() != 0) {
                      rc = p.replyHeader.getErr();
                  }
                  if (p.cb == null) {
                      LOG.warn("Somehow a null cb got to EventThread!");
                  } else if (p.response instanceof ExistsResponse
                          || p.response instanceof SetDataResponse
                          || p.response instanceof SetACLResponse) {
                      StatCallback cb = (StatCallback) p.cb;
                      if (rc == 0) {
                          if (p.response instanceof ExistsResponse) {
                              cb.processResult(rc, clientPath, p.ctx,
                                      ((ExistsResponse) p.response)
                                              .getStat());
                          } else if (p.response instanceof SetDataResponse) {
                              cb.processResult(rc, clientPath, p.ctx,
                                      ((SetDataResponse) p.response)
                                              .getStat());
                          } else if (p.response instanceof SetACLResponse) {
                              cb.processResult(rc, clientPath, p.ctx,
                                      ((SetACLResponse) p.response)
                                              .getStat());
                          }
                      } else {
                          cb.processResult(rc, clientPath, p.ctx, null);
                      }
                  } else if (p.response instanceof GetDataResponse) {
                      DataCallback cb = (DataCallback) p.cb;
                      GetDataResponse rsp = (GetDataResponse) p.response;
                      if (rc == 0) {
                          cb.processResult(rc, clientPath, p.ctx, rsp
                                  .getData(), rsp.getStat());
                      } else {
                          cb.processResult(rc, clientPath, p.ctx, null,
                                  null);
                      }
                  } else if (p.response instanceof GetACLResponse) {
                      ACLCallback cb = (ACLCallback) p.cb;
                      GetACLResponse rsp = (GetACLResponse) p.response;
                      if (rc == 0) {
                          cb.processResult(rc, clientPath, p.ctx, rsp
                                  .getAcl(), rsp.getStat());
                      } else {
                          cb.processResult(rc, clientPath, p.ctx, null,
                                  null);
                      }
                  } else if (p.response instanceof GetChildrenResponse) {
                      ChildrenCallback cb = (ChildrenCallback) p.cb;
                      GetChildrenResponse rsp = (GetChildrenResponse) p.response;
                      if (rc == 0) {
                          cb.processResult(rc, clientPath, p.ctx, rsp
                                  .getChildren());
                      } else {
                          cb.processResult(rc, clientPath, p.ctx, null);
                      }
                  } else if (p.response instanceof GetChildren2Response) {
                      Children2Callback cb = (Children2Callback) p.cb;
                      GetChildren2Response rsp = (GetChildren2Response) p.response;
                      if (rc == 0) {
                          cb.processResult(rc, clientPath, p.ctx, rsp
                                  .getChildren(), rsp.getStat());
                      } else {
                          cb.processResult(rc, clientPath, p.ctx, null, null);
                      }
                  } else if (p.response instanceof CreateResponse) {
                      StringCallback cb = (StringCallback) p.cb;
                      CreateResponse rsp = (CreateResponse) p.response;
                      if (rc == 0) {
                          cb.processResult(rc, clientPath, p.ctx,
                                  (chrootPath == null
                                          ? rsp.getPath()
                                          : rsp.getPath()
                                    .substring(chrootPath.length())));
                      } else {
                          cb.processResult(rc, clientPath, p.ctx, null);
                      }
                  } else if (p.cb instanceof VoidCallback) {
                      VoidCallback cb = (VoidCallback) p.cb;
                      cb.processResult(rc, clientPath, p.ctx);
                  }
              }
          } catch (Throwable t) {
              LOG.error("Caught unexpected throwable", t);
          }
       }
