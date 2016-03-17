/**
 * ACL�ࣺ�ڵ�ķ���Ȩ�ޡ�
 */
perm : ����Ĳ���
id����Ȩ���ʽڵ�������Ϣ

    /**
     *Ȩ�޼��
     */
    static void checkACL(ZooKeeperServer zks, List<ACL> acl, int perm,
            List<Id> ids) throws KeeperException.NoAuthException {
        if (skipACL) {
            return;
        }
        if (acl == null || acl.size() == 0) {
            return;
        }
        for (Id authId : ids) {
            if (authId.getScheme().equals("super")) {
                return;
            }
        }
        for (ACL a : acl) {
            Id id = a.getId();
            if ((a.getPerms() & perm) != 0) {
                if (id.getScheme().equals("world") && id.getId().equals("anyone")) {
                    /**
                     * (a.getPerms() & perm) != 0)��ʾ�����ڵ�����ǰ�Ĳ������ͣ�����create
                     * ͬʱ����ǰ�ķ������Ϊ�κ��˶����Է��ʣ�
                     * ��Ȩ�޼��Ϸ���ֱ�ӷ���
                     */
                    return;
                }
                /**
                 *������ڵ�����ǰ�Ĳ������ͣ������õķ�����ݲ����κ��˶����Է���
                 *��ʱ������Ҫ�жϿͻ��˵�����Ƿ���Ͻڵ����õ���ݡ� ������ϣ���ֱ�ӷ���
                 */
                AuthenticationProvider ap = ProviderRegistry.getProvider(id .getScheme());
                if (ap != null) {
                    for (Id authId : ids) {                        
                        if (authId.getScheme().equals(id.getScheme()) && ap.matches(authId.getId(), id.getId())) {
                            return;
                        }
                    }
                }
            }
        }
        /**
         *���������������������ϣ����׳��쳣�������ͻ�������쳣
         */
        throw new KeeperException.NoAuthException();
    }
