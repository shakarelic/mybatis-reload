package com.admin.reload.controller;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSession.StrictMap;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StopWatch;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.WebApplicationContext;

/**
 *
 * 切莫用于生产环境（后果自负）
 *
 * mybatis映射文件热加载（发生变动后自动重新加载）.
 *
 * 方便开发时使用，不用每次修改xml文件后都要去重启应用.
 *
 * 特性： 1.支持不同的数据源。 2.双线程实时监控，一个用来监控全局，一个用来实时监控热点文件。（100ms）（热点文件2分钟内没续修改自动过期）
 * 3.对于CPU不给力和映射文件庞大的应用，有一定程度的性能问题。
 *
 * 常用的 spring+mybatis
 *
 */
@Component
public class MybatisXmlMapperAutoReloader implements DisposableBean, InitializingBean, ApplicationContextAware {

	private ApplicationContext applicationContext;
	private ScheduledExecutorService pool;

	// 多数据源的场景使用
	private Boolean enableAutoReload = true; // 是否启用热加载.

	/**
	 * 是否启用热加载.
	 * 
	 * @param config
	 */
	public void setEnableAutoReload(Boolean enableAutoReload) {
		this.enableAutoReload = enableAutoReload;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void afterPropertiesSet() throws Exception {

		// 检查设置
		if (!enableAutoReload) {
			System.out.println("禁用：mybatis自动热加载！");
			return;
		} else {
			System.out.println("启用：mybatis自动热加载");
		}

		Map<String, ?> map = checkProperties();

		// 初始化线程池2个（避免线程来回切换）（一个用来监控全局，一个用来实时监控热点文件.）
		pool = Executors.newScheduledThreadPool(2);
		// 配置扫描器.
		final AutoReloadScanner scaner = new AutoReloadScanner((List<SqlSessionFactory>) map.get("sqlSessionFactorys"),
				(List<Resource[]>) map.get("mapperLocationss"));
		// 扫描全部（2s一次）
		pool.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				scaner.scanAllFileChange();
			}
		}, 2, 2, TimeUnit.SECONDS);

		// 扫描热点文件（100ms一次，监控更为频繁）
		pool.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				scaner.scanHotspotFileChange();
			}
		}, 2, 100, TimeUnit.MILLISECONDS);

		System.out.println("启动mybatis自动热加载");
	}

	/**
	 * 检查属性，如果没有设置，直接初始化成默认的方式.
	 */
	private Map<String, ?> checkProperties() {
		WebApplicationContext wac = ContextLoader.getCurrentWebApplicationContext();

		List<Resource[]> mapperLocationss = new ArrayList<>();
		List<SqlSessionFactory> sqlSessionFactorys = new ArrayList<>();
		try {
			for (SqlSessionFactoryBean sqlSessionFactoryBean : wac.getBeansOfType(SqlSessionFactoryBean.class)
					.values()) {
				Field field1 = sqlSessionFactoryBean.getClass().getDeclaredField("sqlSessionFactory");
				field1.setAccessible(true);
				sqlSessionFactorys.add((SqlSessionFactory) field1.get(sqlSessionFactoryBean));

				Field field = sqlSessionFactoryBean.getClass().getDeclaredField("mapperLocations");
				field.setAccessible(true);
				mapperLocationss.add(((Resource[]) field.get(sqlSessionFactoryBean)));
			}
		} catch (Exception e) {
			throw new RuntimeException("获取数据源失败！", e);
		}
		Map<String, Object> map = new HashMap<>();
		map.put("sqlSessionFactorys", sqlSessionFactorys);
		map.put("mapperLocationss", mapperLocationss);
		return map;
	}

	/**
	 * 获取xml文件资源.
	 *
	 * @param basePackage
	 * @param pattern
	 * @return
	 * @throws IOException
	 */
	public Resource[] getResource(String basePackage) {
		try {
			if (!basePackage.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX)) {
				basePackage = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
						+ ClassUtils.convertClassNameToResourcePath(
								applicationContext.getEnvironment().resolveRequiredPlaceholders(basePackage))
						+ "/" + "**/*.xml";
			}
			Resource[] resources = new PathMatchingResourcePatternResolver().getResources(basePackage);
			return resources;
		} catch (Exception e) {
			throw new RuntimeException("获取xml文件资源失败！basePackage=" + basePackage, e);
		}
	}

	@Override
	public void destroy() throws Exception {
		if (pool == null) {
			return;
		}
		pool.shutdown(); // 是否线程池资源
	}

	/**
	 * 自动重载扫描器的具体实现
	 *
	 *
	 * @author thomas
	 * @date Mar 31, 2016 6:59:34 PM
	 *
	 */
	class AutoReloadScanner {
		
		static final int expireTimes = 600 * 2; // 2分钟内没有继续修改，变成非热点文件.不进行实时监控.
		
		ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
		
		// 所有文件
		Map<String, FileInfo> files = new ConcurrentHashMap<String, FileInfo>();

		class FileInfo {
			String tag;
			List<SqlSessionFactory> sqlSessionFactorys = new ArrayList<>();

			public String getTag() {
				return tag;
			}

			public void setTag(String tag) {
				this.tag = tag;
			}

			public List<SqlSessionFactory> getSqlSessionFactorys() {
				return sqlSessionFactorys;
			}

			public FileInfo(String tag, SqlSessionFactory sqlSessionFactory) {
				super();
				this.tag = tag;
				this.sqlSessionFactorys.add(sqlSessionFactory);
			}

		}

		// 热点文件.
		Map<String, AtomicInteger> hotspot = new ConcurrentHashMap<String, AtomicInteger>();

		public AutoReloadScanner(List<SqlSessionFactory> sqlSessionFactorys, List<Resource[]> resources) {
			for (int i = 0; i < resources.size(); i++) {
				this.start(resources.get(i), sqlSessionFactorys.get(i));
			}
		}

		/**
		 * 只扫描热点文件改变.（热点文件失效：连续600个扫描周期内（1分钟）没有改变）
		 */
		public void scanHotspotFileChange() {

			// 如果热点文件为空，立即返回.
			if (hotspot.isEmpty()) {
				return;
			}

			List<String> list = new ArrayList<String>();
			for (Map.Entry<String, AtomicInteger> e : hotspot.entrySet()) {
				String url = e.getKey();
				AtomicInteger counter = e.getValue();
				if (counter.incrementAndGet() >= expireTimes) {
					// 计数器自增，判断是否超过指定的过期次数
					list.add(url);
				}
				if (hasChange(url, files.get(url).getTag())) {
					reload(url); // 变化，调用重新加载方法
					counter.set(0); // 计数器清零
				}
			}

			// 移除过期的热点文件
			if (!list.isEmpty()) {
				// System.out.println("移除过期的热点文件：list=" + list);
				for (String s : list) {
					hotspot.remove(s);
				}
			}
		}

		/**
		 * 重新加载文件.
		 *
		 * @param url
		 */
		private void reload(String url) {
			StopWatch sw = new StopWatch(
					"mybatis mapper auto reload 【" + url.substring(url.lastIndexOf("classes/") + 8) + "】");
			sw.start();
			Resource r = resourcePatternResolver.getResource(url);
			List<Configuration> configurations = getConfigurations(url);
			try {
				for (Configuration configuration : configurations) {
					XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(r.getInputStream(), configuration,
							r.toString(), configuration.getSqlFragments());
					xmlMapperBuilder.parse();
				}
			} catch (Exception e) {
				throw new RuntimeException("Failed to parse mapping resource: '" + r + "'", e);
			} finally {
				ErrorContext.instance().reset();
			}
			sw.stop();
			System.out.println(sw.shortSummary());
		}

		/**
		 * 扫描所有文件改变.
		 */
		public void scanAllFileChange() {
			for (Map.Entry<String, FileInfo> entry : files.entrySet()) {
				String url = entry.getKey();
				if (hasChange(url, entry.getValue().getTag())) {
					// 变化，判断是否在热点文件中，如果存在，直接忽略，如果不存在，触发重新加载
					if (!hotspot.containsKey(url)) {
						// 添加到热点文件，并且触发重新加载
						hotspot.put(url, new AtomicInteger(0));
						reload(url);
					}
				}
			}
		}

		/**
		 * 判断文件是否变化.
		 *
		 * @param url
		 * @param tag
		 * @return
		 */
		private boolean hasChange(String url, String tag) {
			Resource r = resourcePatternResolver.getResource(url);
			String newTag = getTag(r);
			// 之前的标记和最新的标记不一致，说明文件修改了！
			if (!tag.equals(newTag)) {
				files.get(url).setTag(newTag); // 更新标记
				return true;
			}
			return false;
		}

		/**
		 * 获得文件的标记.
		 * 
		 * @param r
		 * @return
		 */
		private String getTag(Resource r) {
			try {
				StringBuilder sb = new StringBuilder();
				sb.append(r.contentLength());
				sb.append(r.lastModified());
				return sb.toString();
			} catch (IOException e) {
				throw new RuntimeException("获取文件标记信息失败！r=" + r, e);
			}
		}

		/**
		 * 开启扫描服务
		 */
		public void start(Resource[] resources, SqlSessionFactory sqlSessionFactory) {
			FileInfo fi;
			try {
				if (resources != null) {
					for (Resource r : resources) {
						String tag = getTag(r);
						fi = files.get(r.getURL().toString());
						if (fi == null) {
							fi = new FileInfo(tag, sqlSessionFactory);
							files.put(r.getURL().toString(), fi);
						} else {
							fi.getSqlSessionFactorys().add(sqlSessionFactory);
						}
					}
				}
			} catch (Exception e) {
				throw new RuntimeException("初始化扫描服务失败！", e);
			}
		}

		/**
		 * 获取配置信息，必须每次都重新获取，否则重新加载xml不起作用.
		 * 
		 * @return
		 */
		private List<Configuration> getConfigurations(String url) {
			List<Configuration> configurations = new ArrayList<>();
			for (SqlSessionFactory sqlSessionFactory : files.get(url).getSqlSessionFactorys()) {
				Configuration configuration = sqlSessionFactory.getConfiguration();
				removeConfig(configuration);
				configurations.add(configuration);
			}
			return configurations;
		}

		/**
		 * 删除不必要的配置项.
		 * 
		 * @param configuration
		 * @throws Exception
		 */
		private void removeConfig(Configuration configuration) {
			try {
				Class<?> classConfig = configuration.getClass();
				clearMap(classConfig, configuration, "mappedStatements");
				clearMap(classConfig, configuration, "caches");
				clearMap(classConfig, configuration, "resultMaps");
				clearMap(classConfig, configuration, "parameterMaps");
				clearMap(classConfig, configuration, "keyGenerators");
				clearMap(classConfig, configuration, "sqlFragments");
				clearSet(classConfig, configuration, "loadedResources");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		private void clearMap(Class<?> classConfig, Configuration configuration, String fieldName) throws Exception {
			Field field = classConfig.getDeclaredField(fieldName);
			field.setAccessible(true);
			Map mapConfig = (Map) field.get(configuration);
			Map newMap = new StrictMap();
			for (Object key : mapConfig.keySet()) {
				try {
					newMap.put(key, mapConfig.get(key));
				} catch (IllegalArgumentException ex) {
					newMap.put(key, ex.getMessage());
				}
			}
			field.set(configuration, newMap);

			// mapConfig.clear();
		}

		@SuppressWarnings("rawtypes")
		private void clearSet(Class<?> classConfig, Configuration configuration, String fieldName) throws Exception {
			Field field = classConfig.getDeclaredField(fieldName);
			field.setAccessible(true);
			Set setConfig = (Set) field.get(configuration);
			setConfig.clear();
		}

	}

}
