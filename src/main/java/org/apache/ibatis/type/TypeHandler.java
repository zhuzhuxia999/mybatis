/*
 *    Copyright 2009-2012 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * @author Clinton Begin
 */
/**
 * 类型处理器
 * 
 */
public interface TypeHandler<T> {

  //设置参数
  //通过PreparedStatement 为sql语句绑定参数时，会将数据由 jdbcType 类型转换成 java 类型
  void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException;

  //取得结果,供普通select用
  //从 result 中获取数据时会调用该方法，会将数据由 java类型转换为 jdbcType 类型
  T getResult(ResultSet rs, String columnName) throws SQLException;

  //取得结果,供普通select用
  T getResult(ResultSet rs, int columnIndex) throws SQLException;

  //取得结果,供SP用
  T getResult(CallableStatement cs, int columnIndex) throws SQLException;

}
