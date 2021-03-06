package club.supreme.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import lombok.experimental.UtilityClass;

/**
 * 租户上下文持有者类
 *
 * @author Supreme
 */
@UtilityClass
public class TenantContextHolder {

    private final TransmittableThreadLocal<TenantContext> THREAD_LOCAL_TENANT = new TransmittableThreadLocal<>();


    /**
     * 获取当前租户上下文
     *
     * @throws NullPointerException 链式调用时需要注意可能存在 NPE 问题
     * @return null or 当前租户上下文
     */
    public TenantContext getTenantContext() throws NullPointerException {
        return THREAD_LOCAL_TENANT.get();
    }

    /**
     * 设置当前租户上下文
     *
     * @param tenantContext 新上下文，传 null 则为清除
     */
    public void setTenantContext(TenantContext tenantContext) {
        if (tenantContext == null) {
            THREAD_LOCAL_TENANT.remove();
            return;
        }

        THREAD_LOCAL_TENANT.set(tenantContext);
    }

    /**
     * 捷径API-取当前租户ID
     *
     * @return null or 当前租户ID
     */
    public Long getTenantId() {
        TenantContext context = getTenantContext();
        return context == null ? null : context.getTenantId();
    }

    /**
     * 捷径API-取当前租户名
     *
     * @return null or 当前租户名
     */
    public String getTenantName() {
        TenantContext context = getTenantContext();
        return context == null ? null : context.getTenantName();
    }
}