package top.hetao.shiyuanticketmp.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.hetao.shiyuanticketmp.common.context.TenantContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * MyBatis-Plus 全局配置。
 *
 * <p>注册以下拦截器（按顺序）：
 * <ol>
 *   <li><b>多租户拦截器</b>（TenantLineInnerInterceptor）— 自动在 SELECT/UPDATE/DELETE SQL 中拼接 tenant_id 条件</li>
 *   <li><b>分页拦截器</b>（PaginationInnerInterceptor）— 物理分页</li>
 * </ol>
 *
 * <p><b>租户拦截器排除表：</b>部分全局表（如字典表、配置表）不需要租户隔离，
 * 通过 {@link #EXCLUDE_TABLES} 配置排除。
 */
@Configuration
public class MybatisPlusConfig {

    /** 不需要租户隔离的表（全局表 + 无 tenant_id 列的关联表） */
    private static final List<String> EXCLUDE_TABLES = Arrays.asList(
            "sys_config",
            "sys_dict",
            "sys_permission",
            "sys_user_role",
            "sys_role_permission",
            "sys_audit_log",
            "work_order_comment"
    );

    /**
     * 注册 MyBatis-Plus 拦截器链。
     *
     * <p><b>注意顺序：</b>多租户拦截器必须在最前面，否则后续拦截器生成的 SQL
     * 可能不包含 tenant_id 条件，导致数据泄露。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ① 多租户拦截器（最高优先级）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                // 超管角色绕过租户过滤
                if (TenantContext.isAdmin()) {
                    return null; // 返回 null 表示不过滤
                }
                Long tenantId = TenantContext.getTenantId();
                return new LongValue(tenantId != null ? tenantId : 0L);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 超管跳过所有表的租户过滤
                if (TenantContext.isAdmin()) {
                    return true;
                }
                return EXCLUDE_TABLES.contains(tableName);
            }
        }));

        // ② 分页拦截器
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }

    /**
     * 自动填充处理器：createdAt、updatedAt 字段自动填充。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime::now, LocalDateTime.class);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
            }
        };
    }
}
