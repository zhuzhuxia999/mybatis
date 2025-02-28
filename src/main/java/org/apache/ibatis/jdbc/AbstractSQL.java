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
package org.apache.ibatis.jdbc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Clinton Begin
 * @author Jeff Butler
 * @author Adam Gent
 */
/**
 * AbstractSQL, Statement Builder的核心,参见mybatis文档Statement Builders一章
 * 建造者模式
 * 可以参考SQLTest，实际上感觉这个类用处不大
 */
public abstract class AbstractSQL<T> {

  private static final String AND = ") \nAND (";
  private static final String OR = ") \nOR (";

  //SQLStatement 是组装sql的内部类，这里先创建出来，用来给外部类调用使用
  private SQLStatement sql = new SQLStatement();

  public abstract T getSelf();

  public T UPDATE(String table) {
    sql().statementType = SQLStatement.StatementType.UPDATE;
    sql().tables.add(table);
    return getSelf();
  }

  public T SET(String sets) {
    sql().sets.add(sets);
    return getSelf();
  }

  public T INSERT_INTO(String tableName) {
    sql().statementType = SQLStatement.StatementType.INSERT;
    sql().tables.add(tableName);
    return getSelf();
  }

  public T VALUES(String columns, String values) {
    sql().columns.add(columns);
    sql().values.add(values);
    return getSelf();
  }

  //select这个list里加入columns，可以看到下面的方法都是类似的，就是建造者模式，把属性一个个set进去
  public T SELECT(String columns) {
    sql().statementType = SQLStatement.StatementType.SELECT;
    sql().select.add(columns);
    return getSelf();
  }

  public T SELECT_DISTINCT(String columns) {
    sql().distinct = true;
    SELECT(columns);
    return getSelf();
  }

  public T DELETE_FROM(String table) {
    sql().statementType = SQLStatement.StatementType.DELETE;
    sql().tables.add(table);
    return getSelf();
  }

  public T FROM(String table) {
    sql().tables.add(table);
    return getSelf();
  }

  public T JOIN(String join) {
    sql().join.add(join);
    return getSelf();
  }

  public T INNER_JOIN(String join) {
    sql().innerJoin.add(join);
    return getSelf();
  }

  public T LEFT_OUTER_JOIN(String join) {
    sql().leftOuterJoin.add(join);
    return getSelf();
  }

  public T RIGHT_OUTER_JOIN(String join) {
    sql().rightOuterJoin.add(join);
    return getSelf();
  }

  public T OUTER_JOIN(String join) {
    sql().outerJoin.add(join);
    return getSelf();
  }

  public T WHERE(String conditions) {
    sql().where.add(conditions);
    //标记最后一个list
    sql().lastList = sql().where;
    return getSelf();
  }

  public T OR() {
	//最后一个list加上OR
    sql().lastList.add(OR);
    return getSelf();
  }

  public T AND() {
	//最后一个list加上AND
    sql().lastList.add(AND);
    return getSelf();
  }

  public T GROUP_BY(String columns) {
    sql().groupBy.add(columns);
    return getSelf();
  }

  public T HAVING(String conditions) {
    sql().having.add(conditions);
    //标记最后一个list
    sql().lastList = sql().having;
    return getSelf();
  }

  public T ORDER_BY(String columns) {
    sql().orderBy.add(columns);
    return getSelf();
  }

  private SQLStatement sql() {
    return sql;
  }

  public <A extends Appendable> A usingAppender(A a) {
    sql().sql(a);
    return a;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sql().sql(sb);
    return sb.toString();
  }

  //安全的Appendable
  private static class SafeAppendable {
    private final Appendable a;
    private boolean empty = true;

    public SafeAppendable(Appendable a) {
      super();
      this.a = a;
    }

    public SafeAppendable append(CharSequence s) {
      try {
        if (empty && s.length() > 0) {
          empty = false;
        }
        a.append(s);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return this;
    }

    public boolean isEmpty() {
      return empty;
    }

  }

  //SQL语句
  //加工拼接sql都在这个内部类中，然后外部类只是通过对这个内部类的调用组装sql.建造者模式
  private static class SQLStatement {

    //4种语句类型
	public enum StatementType {
      DELETE, INSERT, SELECT, UPDATE
    }

    StatementType statementType;
    List<String> sets = new ArrayList<String>();
    List<String> select = new ArrayList<String>();
    List<String> tables = new ArrayList<String>();
    List<String> join = new ArrayList<String>();
    List<String> innerJoin = new ArrayList<String>();
    List<String> outerJoin = new ArrayList<String>();
    List<String> leftOuterJoin = new ArrayList<String>();
    List<String> rightOuterJoin = new ArrayList<String>();
    List<String> where = new ArrayList<String>();
    List<String> having = new ArrayList<String>();
    List<String> groupBy = new ArrayList<String>();
    List<String> orderBy = new ArrayList<String>();
    //标记最后一个list
    List<String> lastList = new ArrayList<String>();
    List<String> columns = new ArrayList<String>();
    List<String> values = new ArrayList<String>();
    boolean distinct;

    public SQLStatement() {
        // Prevent Synthetic Access
    }

    private void sqlClause(SafeAppendable builder, String keyword, List<String> parts, String open, String close,
                           String conjunction) {
      if (!parts.isEmpty()) {
        if (!builder.isEmpty()) {
          //如果前面有东西，另起一行
          builder.append("\n");
        }
        builder.append(keyword);
        builder.append(" ");
        builder.append(open);
        String last = "________";
        for (int i = 0, n = parts.size(); i < n; i++) {
          String part = parts.get(i);
          if (i > 0 && !part.equals(AND) && !part.equals(OR) && !last.equals(AND) && !last.equals(OR)) {
        	  builder.append(conjunction);
          }
          builder.append(part);
          last = part;
        }
        builder.append(close);
      }
    }

    //拼装select语句,可以看到都是调用sqlClause，
    private String selectSQL(SafeAppendable builder) {
      if (distinct) {
        sqlClause(builder, "SELECT DISTINCT", select, "", "", ", ");
      } else {
        sqlClause(builder, "SELECT", select, "", "", ", ");
      }

      sqlClause(builder, "FROM", tables, "", "", ", ");
      sqlClause(builder, "JOIN", join, "", "", "\nJOIN ");
      sqlClause(builder, "INNER JOIN", innerJoin, "", "", "\nINNER JOIN ");
      sqlClause(builder, "OUTER JOIN", outerJoin, "", "", "\nOUTER JOIN ");
      sqlClause(builder, "LEFT OUTER JOIN", leftOuterJoin, "", "", "\nLEFT OUTER JOIN ");
      sqlClause(builder, "RIGHT OUTER JOIN", rightOuterJoin, "", "", "\nRIGHT OUTER JOIN ");
      //where条件默认拼接上AND
      sqlClause(builder, "WHERE", where, "(", ")", " AND ");
      sqlClause(builder, "GROUP BY", groupBy, "", "", ", ");
      sqlClause(builder, "HAVING", having, "(", ")", " AND ");
      sqlClause(builder, "ORDER BY", orderBy, "", "", ", ");
      return builder.toString();
    }

    private String insertSQL(SafeAppendable builder) {
      sqlClause(builder, "INSERT INTO", tables, "", "", "");
      sqlClause(builder, "", columns, "(", ")", ", ");
      sqlClause(builder, "VALUES", values, "(", ")", ", ");
      return builder.toString();
    }

    private String deleteSQL(SafeAppendable builder) {
      sqlClause(builder, "DELETE FROM", tables, "", "", "");
      sqlClause(builder, "WHERE", where, "(", ")", " AND ");
      return builder.toString();
    }

    private String updateSQL(SafeAppendable builder) {

      sqlClause(builder, "UPDATE", tables, "", "", "");
      sqlClause(builder, "SET", sets, "", "", ", ");
      sqlClause(builder, "WHERE", where, "(", ")", " AND ");
      return builder.toString();
    }

    //拼装SQL
    public String sql(Appendable a) {
      SafeAppendable builder = new SafeAppendable(a);
      if (statementType == null) {
        return null;
      }

      String answer;

      switch (statementType) {
        case DELETE:
          answer = deleteSQL(builder);
          break;

        case INSERT:
          answer = insertSQL(builder);
          break;

        case SELECT:
          answer = selectSQL(builder);
          break;

        case UPDATE:
          answer = updateSQL(builder);
          break;

        default:
          answer = null;
      }

      return answer;
    }
  }
}