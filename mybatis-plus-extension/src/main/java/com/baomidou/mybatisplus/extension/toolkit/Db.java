package com.baomidou.mybatisplus.extension.toolkit;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.*;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 以静态方式调用Service中的函数
 *
 * @author VampireAchao
 * @since 2022-05-03
 */
public class Db {

    private static final Log log = LogFactory.getLog(Db.class);

    private Db() {
        /* Do not new me! */
    }

    /**
     * 插入一条记录（选择字段，策略插入）
     *
     * @param entity 实体对象
     */
    public static <T> boolean save(T entity) {
        if (Objects.isNull(entity)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        Integer result = SqlHelper.execute(entityClass, baseMapper -> baseMapper.insert(entity));
        return SqlHelper.retBool(result);
    }

    /**
     * 插入（批量）
     *
     * @param entityList 实体对象集合
     */
    public static <T> boolean saveBatch(Collection<T> entityList) {
        return saveBatch(entityList, IService.DEFAULT_BATCH_SIZE);
    }

    /**
     * 插入（批量）
     *
     * @param entityList 实体对象集合
     * @param batchSize  插入批次数量
     */
    public static <T> boolean saveBatch(Collection<T> entityList, int batchSize) {
        if (CollectionUtils.isEmpty(entityList)) {
            return false;
        }
        Class<T> entityClass = getEntityClass(entityList);
        Class<?> mapperClass = ClassUtils.toClassConfident(getTableInfo(entityClass).getCurrentNamespace());
        String sqlStatement = SqlHelper.getSqlStatement(mapperClass, SqlMethod.INSERT_ONE);
        return SqlHelper.executeBatch(entityClass, LogFactory.getLog(Db.class), entityList, batchSize, (sqlSession, entity) -> sqlSession.insert(sqlStatement, entity));
    }

    /**
     * 批量修改插入
     *
     * @param entityList 实体对象集合
     */
    public static <T> boolean saveOrUpdateBatch(Collection<T> entityList) {
        return saveOrUpdateBatch(entityList, IService.DEFAULT_BATCH_SIZE);
    }

    /**
     * 批量修改插入
     *
     * @param entityList 实体对象集合
     * @param batchSize  每次的数量
     */
    public static <T> boolean saveOrUpdateBatch(Collection<T> entityList, int batchSize) {
        if (CollectionUtils.isEmpty(entityList)) {
            return false;
        }
        Class<T> entityClass = getEntityClass(entityList);
        TableInfo tableInfo = getTableInfo(entityClass);
        Class<?> mapperClass = ClassUtils.toClassConfident(tableInfo.getCurrentNamespace());
        String keyProperty = tableInfo.getKeyProperty();
        Assert.notEmpty(keyProperty, "error: can not execute. because can not find column for primary key from entity!");
        return SqlHelper.saveOrUpdateBatch(entityClass, mapperClass, LogFactory.getLog(Db.class), entityList, batchSize, (sqlSession, entity) -> {
            Object idVal = tableInfo.getPropertyValue(entity, keyProperty);
            return StringUtils.checkValNull(idVal)
                || CollectionUtils.isEmpty(sqlSession.selectList(SqlHelper.getSqlStatement(mapperClass, SqlMethod.SELECT_BY_ID), entity));
        }, (sqlSession, entity) -> {
            MapperMethod.ParamMap<T> param = new MapperMethod.ParamMap<>();
            param.put(Constants.ENTITY, entity);
            sqlSession.update(SqlHelper.getSqlStatement(mapperClass, SqlMethod.UPDATE_BY_ID), param);
        });
    }

    /**
     * 根据 ID 删除
     *
     * @param id          主键ID
     * @param entityClass 实体类
     */
    public static <T> boolean removeById(Serializable id, Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> SqlHelper.retBool(baseMapper.deleteById(id)));
    }

    /**
     * 根据实体(ID)删除
     *
     * @param entity 实体
     */
    public static <T> boolean removeById(T entity) {
        if (Objects.isNull(entity)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        return SqlHelper.execute(entityClass, baseMapper -> SqlHelper.retBool(baseMapper.deleteById(entity)));
    }

    /**
     * 根据 entity 条件，删除记录
     *
     * @param queryWrapper 实体包装类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T> boolean remove(AbstractWrapper<T, ?, ?> queryWrapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> SqlHelper.retBool(baseMapper.delete(queryWrapper)));
    }

    /**
     * 根据 ID 选择修改
     *
     * @param entity 实体对象
     */
    public static <T> boolean updateById(T entity) {
        if (Objects.isNull(entity)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        return SqlHelper.execute(entityClass, baseMapper -> SqlHelper.retBool(baseMapper.updateById(entity)));
    }

    /**
     * 根据 UpdateWrapper 条件，更新记录 需要设置sqlset
     *
     * @param updateWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper}
     */
    public static <T> boolean update(AbstractWrapper<T, ?, ?> updateWrapper) {
        return update(null, updateWrapper);
    }

    /**
     * 根据 whereEntity 条件，更新记录
     *
     * @param entity        实体对象
     * @param updateWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper}
     */
    public static <T> boolean update(T entity, AbstractWrapper<T, ?, ?> updateWrapper) {
        return SqlHelper.execute(getEntityClass(updateWrapper), baseMapper -> SqlHelper.retBool(baseMapper.update(entity, updateWrapper)));
    }

    /**
     * 根据ID 批量更新
     *
     * @param entityList 实体对象集合
     */
    public static <T> boolean updateBatchById(Collection<T> entityList) {
        return updateBatchById(entityList, IService.DEFAULT_BATCH_SIZE);
    }

    /**
     * 根据ID 批量更新
     *
     * @param entityList 实体对象集合
     * @param batchSize  更新批次数量
     */
    public static <T> boolean updateBatchById(Collection<T> entityList, int batchSize) {
        Class<T> entityClass = getEntityClass(entityList);
        TableInfo tableInfo = getTableInfo(entityClass);
        String sqlStatement = SqlHelper.getSqlStatement(ClassUtils.toClassConfident(tableInfo.getCurrentNamespace()), SqlMethod.UPDATE_BY_ID);
        return SqlHelper.executeBatch(entityClass, LogFactory.getLog(Db.class), entityList, batchSize, (sqlSession, entity) -> {
            MapperMethod.ParamMap<T> param = new MapperMethod.ParamMap<>();
            param.put(Constants.ENTITY, entity);
            sqlSession.update(sqlStatement, param);
        });
    }

    /**
     * 删除（根据ID 批量删除）
     *
     * @param list        主键ID或实体列表
     * @param entityClass 实体类
     */
    public static <T> boolean removeByIds(Collection<? extends Serializable> list, Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> SqlHelper.retBool(baseMapper.deleteBatchIds(list)));
    }

    /**
     * 根据 columnMap 条件，删除记录
     *
     * @param columnMap   表字段 map 对象
     * @param entityClass 实体类
     */
    public static <T> boolean removeByMap(Map<String, Object> columnMap, Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> SqlHelper.retBool(baseMapper.deleteByMap(columnMap)));
    }

    /**
     * TableId 注解存在更新记录，否插入一条记录
     *
     * @param entity 实体对象
     */
    public static <T> boolean saveOrUpdate(T entity) {
        if (Objects.isNull(entity)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        TableInfo tableInfo = TableInfoHelper.getTableInfo(entityClass);
        Assert.notNull(tableInfo, "error: can not execute. because can not find cache of TableInfo for entity!");
        String keyProperty = tableInfo.getKeyProperty();
        Assert.notEmpty(keyProperty, "error: can not execute. because can not find column for id from entity!");
        Object idVal = tableInfo.getPropertyValue(entity, tableInfo.getKeyProperty());
        return StringUtils.checkValNull(idVal) || Objects.isNull(getById((Serializable) idVal, entityClass)) ? save(entity) : updateById(entity);
    }

    /**
     * 根据 ID 查询
     *
     * @param id          主键ID
     * @param entityClass 实体类
     */
    public static <T> T getById(Serializable id, Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectById(id));
    }

    /**
     * 根据 Wrapper，查询一条记录 <br/>
     * <p>结果集，如果是多个会抛出异常，随机取一条加上限制条件 wrapper.last("LIMIT 1")</p>
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T> T getOne(AbstractWrapper<T, ?, ?> queryWrapper) {
        return getOne(queryWrapper, true);
    }

    /**
     * 根据 Wrapper，查询一条记录
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     * @param throwEx      有多个 result 是否抛出异常
     */
    public static <T> T getOne(AbstractWrapper<T, ?, ?> queryWrapper, boolean throwEx) {
        Class<T> entityClass = getEntityClass(queryWrapper);
        if (throwEx) {
            return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectOne(queryWrapper));
        }
        return SqlHelper.execute(entityClass, baseMapper -> SqlHelper.getObject(log, baseMapper.selectList(queryWrapper)));
    }

    /**
     * 查询（根据 columnMap 条件）
     *
     * @param columnMap   表字段 map 对象
     * @param entityClass 实体类
     */
    public static <T> List<T> listByMap(Map<String, Object> columnMap, Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectByMap(columnMap));
    }

    /**
     * 查询（根据ID 批量查询）
     *
     * @param idList      主键ID列表
     * @param entityClass 实体类
     */
    public static <T> List<T> listByIds(Collection<? extends Serializable> idList, Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectBatchIds(idList));
    }

    /**
     * 根据 Wrapper，查询一条记录
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T> Map<String, Object> getMap(AbstractWrapper<T, ?, ?> queryWrapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> SqlHelper.getObject(log, baseMapper.selectMaps(queryWrapper)));
    }

    /**
     * 查询总记录数
     *
     * @param entityClass 实体类
     * @see Wrappers#emptyWrapper()
     */
    public static <T> long count(Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectCount(null));
    }

    /**
     * 根据 Wrapper 条件，查询总记录数
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T> long count(AbstractWrapper<T, ?, ?> queryWrapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> baseMapper.selectCount(queryWrapper));
    }

    /**
     * 查询列表
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T> List<T> list(AbstractWrapper<T, ?, ?> queryWrapper) {
        Class<T> entityClass = getEntityClass(queryWrapper);
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectList(queryWrapper));
    }

    /**
     * 查询所有
     *
     * @param entityClass 实体类
     * @see Wrappers#emptyWrapper()
     */
    public static <T> List<T> list(Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectList(null));
    }

    /**
     * 查询列表
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T> List<Map<String, Object>> listMaps(AbstractWrapper<T, ?, ?> queryWrapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> baseMapper.selectMaps(queryWrapper));
    }

    /**
     * 查询所有列表
     *
     * @param entityClass 实体类
     * @see Wrappers#emptyWrapper()
     */
    public static <T> List<Map<String, Object>> listMaps(Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectMaps(null));
    }

    /**
     * 查询全部记录
     *
     * @param entityClass 实体类
     */
    public static <T> List<T> listObjs(Class<T> entityClass) {
        return listObjs(entityClass, i -> i);
    }

    /**
     * 根据 Wrapper 条件，查询全部记录
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T> List<Object> listObjs(AbstractWrapper<T, ?, ?> queryWrapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> baseMapper.selectObjs(queryWrapper));
    }

    /**
     * 根据 Wrapper 条件，查询全部记录
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     * @param mapper       转换函数
     */
    public static <T, V> List<V> listObjs(AbstractWrapper<T, ?, ?> queryWrapper, SFunction<? super T, V> mapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> baseMapper.selectList(queryWrapper).stream().map(mapper).collect(Collectors.toList()));
    }

    /**
     * 查询全部记录
     *
     * @param entityClass 实体类
     * @param mapper      转换函数
     */
    public static <T, V> List<V> listObjs(Class<T> entityClass, SFunction<? super T, V> mapper) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectList(null).stream().map(mapper).collect(Collectors.toList()));
    }

    /**
     * 无条件翻页查询
     *
     * @param page        翻页对象
     * @param entityClass 实体类
     * @see Wrappers#emptyWrapper()
     */
    public static <T, E extends IPage<Map<String, Object>>> E pageMaps(E page, Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectMapsPage(page, null));
    }

    /**
     * 翻页查询
     *
     * @param page         翻页对象
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T, E extends IPage<Map<String, Object>>> E pageMaps(E page, AbstractWrapper<T, ?, ?> queryWrapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> baseMapper.selectMapsPage(page, queryWrapper));
    }

    /**
     * 无条件翻页查询
     *
     * @param page        翻页对象
     * @param entityClass 实体类
     * @see Wrappers#emptyWrapper()
     */
    public static <T> IPage<T> page(IPage<T> page, Class<T> entityClass) {
        return SqlHelper.execute(entityClass, baseMapper -> baseMapper.selectPage(page, null));
    }

    /**
     * 翻页查询
     *
     * @param page         翻页对象
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     */
    public static <T> IPage<T> page(IPage<T> page, AbstractWrapper<T, ?, ?> queryWrapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> baseMapper.selectPage(page, queryWrapper));
    }

    /**
     * 链式查询 普通
     *
     * @return QueryWrapper 的包装类
     */
    public static <T> QueryChainWrapper<T> query(Class<T> entityClass) {
        return ChainWrappers.queryChain(entityClass);
    }

    /**
     * 链式查询 lambda 式
     * <p>注意：不支持 Kotlin </p>
     *
     * @return LambdaQueryWrapper 的包装类
     */
    public static <T> LambdaQueryChainWrapper<T> lambdaQuery(Class<T> entityClass) {
        return ChainWrappers.lambdaQueryChain(entityClass);
    }

    /**
     * 链式更改 普通
     *
     * @return UpdateWrapper 的包装类
     */
    public static <T> UpdateChainWrapper<T> update(Class<T> entityClass) {
        return ChainWrappers.updateChain(entityClass);
    }

    /**
     * 链式更改 lambda 式
     * <p>注意：不支持 Kotlin </p>
     *
     * @return LambdaUpdateWrapper 的包装类
     */
    public static <T> LambdaUpdateChainWrapper<T> lambdaUpdate(Class<T> entityClass) {
        return ChainWrappers.lambdaUpdateChain(entityClass);
    }

    /**
     * <p>
     * 根据updateWrapper尝试更新，否继续执行saveOrUpdate(T)方法
     * 此次修改主要是减少了此项业务代码的代码量（存在性验证之后的saveOrUpdate操作）
     * </p>
     *
     * @param entity 实体对象
     */
    public static <T> boolean saveOrUpdate(T entity, AbstractWrapper<T, ?, ?> updateWrapper) {
        return update(entity, updateWrapper) || saveOrUpdate(entity);
    }

    /**
     * 根据 Wrapper，查询一条记录
     *
     * @param queryWrapper 实体对象封装操作类 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}
     * @param mapper       转换函数
     */
    public static <T, V> V getObj(AbstractWrapper<T, ?, ?> queryWrapper, SFunction<? super T, V> mapper) {
        return SqlHelper.execute(getEntityClass(queryWrapper), baseMapper -> mapper.apply(baseMapper.selectOne(queryWrapper)));
    }

    /**
     * 从集合中获取实体类型
     *
     * @param entityList 实体集合
     * @param <T>        实体类型
     * @return 实体类型
     */
    @SuppressWarnings("unchecked")
    protected static <T> Class<T> getEntityClass(Collection<T> entityList) {
        Class<T> entityClass = null;
        for (T entity : entityList) {
            if (entity != null && entity.getClass() != null) {
                entityClass = (Class<T>) entity.getClass();
                break;
            }
        }
        Assert.notNull(entityClass, "error: can not get entityClass from entityList");
        return entityClass;
    }

    /**
     * 从wrapper中尝试获取实体类型
     *
     * @param queryWrapper 条件构造器
     * @param <T>          实体类型
     * @return 实体类型
     */
    @SuppressWarnings("unchecked")
    protected static <T> Class<T> getEntityClass(AbstractWrapper<T, ?, ?> queryWrapper) {
        Class<T> entityClass = queryWrapper.getEntityClass();
        if (entityClass == null) {
            T entity = queryWrapper.getEntity();
            if (entity != null) {
                entityClass = (Class<T>) entity.getClass();
            }
        }
        Assert.notNull(entityClass, "error: can not get entityClass from wrapper");
        return entityClass;
    }

    /**
     * 获取表信息，获取不到报错提示
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 对应表信息
     */
    protected static <T> TableInfo getTableInfo(Class<T> entityClass) {
        return Optional.ofNullable(TableInfoHelper.getTableInfo(entityClass)).orElseThrow(() -> ExceptionUtils.mpe("error: can not find TableInfo from Class: \"%s\".", entityClass.getName()));
    }
}
