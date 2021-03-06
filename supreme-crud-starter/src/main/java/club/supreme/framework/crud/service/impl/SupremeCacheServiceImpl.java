package club.supreme.framework.crud.service.impl;

import club.supreme.framework.cache.repository.CacheOps;
import club.supreme.framework.crud.mapper.SupremeBaseMapper;
import club.supreme.framework.crud.service.SupremeCacheService;
import club.supreme.framework.model.SupremeBaseEntity;
import club.supreme.framework.model.cache.CacheKey;
import club.supreme.framework.model.cache.CacheKeyBuilder;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ReflectUtil;
import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.*;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基于 CacheOps 实现的 缓存实现
 * 默认的key规则： #{CacheKeyBuilder#key()}:id
 * <p>
 * 1，getByIdCache：新增的方法： 先查缓存，在查db
 * 2，removeById：重写 ServiceImpl 类的方法，删除db后，淘汰缓存
 * 3，removeByIds：重写 ServiceImpl 类的方法，删除db后，淘汰缓存
 * 4，updateAllById： 新增的方法： 修改数据（所有字段）后，淘汰缓存
 * 5，updateById：重写 ServiceImpl 类的方法，修改db后，淘汰缓存
 *
 * @param <MAPPER>
 * @param <ENTITY>
 * @author supreme
 * @date 2020年02月27日18:15:17
 */
public abstract class SupremeCacheServiceImpl<MAPPER extends SupremeBaseMapper<ENTITY>, ENTITY>
        extends SupremeBaseServiceImpl<MAPPER, ENTITY> implements SupremeCacheService<ENTITY> {

    @Autowired
    protected CacheOps cacheOps;

    protected static final int MAX_BATCH_KEY_SIZE = 20;

    /**
     * 缓存key 构造器
     *
     * @return 缓存key构造器
     */
    protected abstract CacheKeyBuilder cacheKeyBuilder();

    @Override
    @Transactional(readOnly = true)
    public ENTITY getByIdCache(Serializable id) {
        CacheKey cacheKey = cacheKeyBuilder().key(id);
        return cacheOps.get(cacheKey, k -> super.getById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ENTITY> findByIds(@NonNull Collection<? extends Serializable> ids, Function<Collection<? extends Serializable>, Collection<ENTITY>> loader) {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        // 拼接keys
        List<CacheKey> keys = ids.stream().map(cacheKeyBuilder()::key).collect(Collectors.toList());
        // 切割
        List<List<CacheKey>> partitionKeys = Lists.partition(keys, MAX_BATCH_KEY_SIZE);

        // 用切割后的 partitionKeys 分批去缓存查， 返回的是缓存中存在的数据
        List<ENTITY> valueList = partitionKeys.stream().map(ks -> new ArrayList<ENTITY>(cacheOps.find(ks))).flatMap(Collection::stream).collect(Collectors.toList());

        // 所有的key
        List<Serializable> keysList = Lists.newArrayList(ids);
        // 缓存不存在的key
        Set<Serializable> missedKeys = Sets.newLinkedHashSet();

        List<ENTITY> allList = new ArrayList<>();
        for (int i = 0; i < valueList.size(); i++) {
            ENTITY v = valueList.get(i);
            Serializable k = keysList.get(i);
            if (v == null) {
                missedKeys.add(k);
            } else {
                allList.add(v);
            }
        }
        // 加载miss 的数据，并设置到缓存
        if (CollUtil.isNotEmpty(missedKeys)) {
            if (loader == null) {
                loader = this::listByIds;
            }
            Collection<ENTITY> missList = loader.apply(missedKeys);
            missList.forEach(this::setCache);
            allList.addAll(missList);
        }
        return allList;
    }

    @Override
    @Transactional(readOnly = true)
    public ENTITY getByKey(CacheKey key, Function<CacheKey, Object> loader) {
        Object id = cacheOps.get(key, loader);
        return id == null ? null : getByIdCache(Convert.toLong(id));
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        boolean bool = super.removeById(id);
        delCache(id);
        return bool;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeByIds(Collection<? extends Serializable> idList) {
        if (CollUtil.isEmpty(idList)) {
            return true;
        }
        boolean flag = super.removeByIds(idList);

        delCache(idList);
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(ENTITY model) {
        boolean save = super.save(model);
        setCache(model);
        return save;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateAllById(ENTITY model) {
        boolean updateBool = super.updateAllById(model);
        delCache(model);
        return updateBool;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(ENTITY model) {
        boolean updateBool = super.updateById(model);
        delCache(model);
        return updateBool;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveBatch(Collection<ENTITY> entityList, int batchSize) {
        String sqlStatement = getSqlStatement(SqlMethod.INSERT_ONE);
        return executeBatch(entityList, batchSize, (sqlSession, entity) -> {
            sqlSession.insert(sqlStatement, entity);

            // 设置缓存
            setCache(entity);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean saveOrUpdateBatch(Collection<ENTITY> entityList, int batchSize) {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(getEntityClass());
        Assert.notNull(tableInfo, "error: can not execute. because can not find cache of TableInfo for entity!");
        String keyProperty = tableInfo.getKeyProperty();
        Assert.notEmpty(keyProperty, "error: can not execute. because can not find column for id from entity!");

        BiPredicate<SqlSession, ENTITY> predicate = (sqlSession, entity) -> {
            Object idVal = ReflectionKit.getFieldValue(entity, keyProperty);
            return StringUtils.checkValNull(idVal)
                    || CollectionUtils.isEmpty(sqlSession.selectList(getSqlStatement(SqlMethod.SELECT_BY_ID), entity));
        };

        BiConsumer<SqlSession, ENTITY> consumer = (sqlSession, entity) -> {
            MapperMethod.ParamMap<ENTITY> param = new MapperMethod.ParamMap<>();
            param.put(Constants.ENTITY, entity);
            sqlSession.update(getSqlStatement(SqlMethod.UPDATE_BY_ID), param);

            // 清理缓存
            delCache(entity);
        };

        String sqlStatement = SqlHelper.getSqlStatement(this.mapperClass, SqlMethod.INSERT_ONE);
        return SqlHelper.executeBatch(getEntityClass(), log, entityList, batchSize, (sqlSession, entity) -> {
            if (predicate.test(sqlSession, entity)) {
                sqlSession.insert(sqlStatement, entity);
                // 设置缓存
                setCache(entity);
            } else {
                consumer.accept(sqlSession, entity);
            }
        });


    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean updateBatchById(Collection<ENTITY> entityList, int batchSize) {
        String sqlStatement = getSqlStatement(SqlMethod.UPDATE_BY_ID);
        return executeBatch(entityList, batchSize, (sqlSession, entity) -> {
            MapperMethod.ParamMap<ENTITY> param = new MapperMethod.ParamMap<>();
            param.put(Constants.ENTITY, entity);
            sqlSession.update(sqlStatement, param);

            // 清理缓存
            delCache(entity);
        });
    }

    @Override
    public void refreshCache() {
        list().forEach(this::setCache);
    }

    @Override
    public void clearCache() {
        list().forEach(this::delCache);
    }


    protected void delCache(Serializable... ids) {
        delCache(Arrays.asList(ids));
    }

    protected void delCache(Collection<? extends Serializable> idList) {
        CacheKey[] keys = idList.stream().map(id -> cacheKeyBuilder().key(id)).toArray(CacheKey[]::new);
        cacheOps.del(keys);
    }

    protected void delCache(ENTITY model) {
        Object id = getId(model);
        if (id != null) {
            CacheKey key = cacheKeyBuilder().key(id);
            cacheOps.del(key);
        }
    }

    protected void setCache(ENTITY model) {
        Object id = getId(model);
        if (id != null) {
            CacheKey key = cacheKeyBuilder().key(id);
            cacheOps.set(key, model);
        }
    }

    protected Object getId(ENTITY model) {
        if (model instanceof SupremeBaseEntity) {
            return ((SupremeBaseEntity) model).getId();
        } else {
            // 实体没有继承 Entity 和 SuperEntity
            TableInfo tableInfo = TableInfoHelper.getTableInfo(getEntityClass());
            if (tableInfo == null) {
                return null;
            }
            // 主键类型
            Class<?> keyType = tableInfo.getKeyType();
            if (keyType == null) {
                return null;
            }
            // id 字段名
            String keyProperty = tableInfo.getKeyProperty();

            // 反射得到 主键的值
            Field idField = ReflectUtil.getField(getEntityClass(), keyProperty);
            return ReflectUtil.getFieldValue(model, idField);
        }
    }

}
