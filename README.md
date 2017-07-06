# mybatis-reload
mybatis reload 热加载


 * 切莫用于生产环境（后果自负）
 *
 * mybatis映射文件热加载（发生变动后自动重新加载）.
 *
 * 方便开发时使用，不用每次修改xml文件后都要去重启应用.
 *
 * 监听具体sql xml修改
 *
 * 特性： 
 * 1.支持不同的数据源 
 * 2.支持多数据源
 * 3.双线程实时监控，一个用来监控全局，一个用来实时监控热点文件。（100ms）（热点文件2分钟内没续修改自动过期）
 * 4.对于CPU不给力和映射文件庞大的应用，有一定程度的性能问题。
 * 5.常用的 spring+mybatis 就可以使用
 
 MybatisXmlMapperAutoReloader 导入到你的项目，保证spring能扫描加载它就可以了  
 Spring sqlSessionFactory xml部分配置  
  
	<!-- （必须存在）-->  
	<bean id="sqlSessionFactory" class="org.mybatis.spring.SqlSessionFactoryBean">   
		<!-- 匹配Mapper映射文件 (必须存在)-->
		<property name="mapperLocations" value="classpath:mybatis/mapper/*/*.xml" />  
		<property name="dataSource" ref="dataSource" />  
	</bean>
  
