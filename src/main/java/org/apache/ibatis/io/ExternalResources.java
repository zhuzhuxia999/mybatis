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
package org.apache.ibatis.io;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Properties;

/**
 * @author Clinton Begin
 * 类似于commons-io里的一些util方法,不过实际上没有任何地方用到了这个类
 */
public class ExternalResources {

  private ExternalResources() {
    // do nothing
  }

  //复制文件
  public static void copyExternalResource(File sourceFile, File destFile) throws IOException {
    if (!destFile.exists()) {
      destFile.createNewFile();
    }

    FileChannel source = null;
    FileChannel destination = null;
    try {
      source = new FileInputStream(sourceFile).getChannel();
      destination = new FileOutputStream(destFile).getChannel();
      destination.transferFrom(source, 0, source.size());
    } finally {
      closeQuietly(source);
      closeQuietly(destination);
    }

  }

  //安静地关闭
  private static void closeQuietly(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        // do nothing, close quietly
        //好吧 我可以理解为静悄悄的关闭，日志都不留的意思吗 哈哈
      }
    }
  }

  //读取property
  //从对应路径 templatePath 下读取 想要（templateProperty）的属性
  public static String getConfiguredTemplate(String templatePath, String templateProperty) throws FileNotFoundException {
    String templateName = "";
    Properties migrationProperties = new Properties();

    try {
      migrationProperties.load(new FileInputStream(templatePath));
      templateName = migrationProperties.getProperty(templateProperty);
    } catch (FileNotFoundException e) {
      throw e;
    } catch (Exception e) {
      e.printStackTrace();
    }

    return templateName;
  }

}
