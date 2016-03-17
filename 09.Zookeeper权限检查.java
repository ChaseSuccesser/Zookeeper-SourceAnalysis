/**
 * ACL类：节点的访问权限·
 */
perm : 允许的操作
id：有权访问节点的身份信息

    /**
     *权限检查
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
                     * (a.getPerms() & perm) != 0)表示：父节点允许当前的操作类型，例如create
                     * 同时，当前的访问身份为任何人都可以访问，
                     * 则权限检查合法，直接返回
                     */
                    return;
                }
                /**
                 *如果父节点允许当前的操作类型，但设置的访问身份不是任何人都可以访问
                 *这时，就需要判断客户端的身份是否符合节点设置的身份。 如果符合，则直接返回
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
         *如果以上两种情况都不符合，则抛出异常，表明客户端身份异常
         */
        throw new KeeperException.NoAuthException();
    }
