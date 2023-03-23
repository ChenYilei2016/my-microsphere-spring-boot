/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.microsphere.spring.boot.context.properties.bind;

import io.github.microsphere.spring.context.event.JavaBeansPropertyChangeListenerAdapter;
import io.github.microsphere.spring.core.convert.support.ConversionServiceResolver;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.convert.ConversionService;
import org.springframework.util.ClassUtils;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.github.microsphere.spring.boot.context.properties.source.util.ConfigurationPropertyUtils.toDashedForm;
import static org.springframework.beans.BeanUtils.copyProperties;

/**
 * The context for the bean annotated {@link ConfigurationProperties @ConfigurationProperties}
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 * @since 1.0.0
 */
class ConfigurationPropertiesBeanContext {

    private final ConfigurationProperties annotation;

    private final String prefix;

    private final ConfigurableApplicationContext context;

    private final BeanWrapperImpl initializedBeanWrapper;

    private Map<String, String> bindingPropertyNames;

    private volatile Object bean;
    private volatile PropertyChangeSupport propertyChangeSupport;

    public ConfigurationPropertiesBeanContext(Class<?> beanClass, ConfigurationProperties annotation, String prefix, ConfigurableApplicationContext context) {
        // TODO support @ConstructorBinding creating beans
        this.annotation = annotation;
        this.prefix = prefix;
        this.context = context;
        this.initializedBeanWrapper = createInitializedBeanWrapper(beanClass);
    }

    private BeanWrapperImpl createInitializedBeanWrapper(Class<?> beanClass) {
        BeanWrapperImpl beanWrapper = new BeanWrapperImpl(beanClass);
        ConversionService conversionService = getConversionService(context);
        beanWrapper.setAutoGrowNestedPaths(true);
        beanWrapper.setConversionService(conversionService);
        return beanWrapper;
    }

    private ConversionService getConversionService(ConfigurableApplicationContext context) {
        return new ConversionServiceResolver(context.getBeanFactory()).resolve();
    }

    protected void initialize(Object bean) {
        this.bean = bean;
        setProperties(bean);
        initBinding(bean);
        initPropertyChangeSupport(bean);
    }

    private void setProperties(Object bean) {
        Object initializedBean = this.initializedBeanWrapper.getWrappedInstance();
        copyProperties(bean, initializedBean);
    }

    private void initBinding(Object bean) {
        Map<String, String> bindingPropertyNames = new HashMap<>();
        String prefix = getPrefix();
        initBinding(bean.getClass(), prefix, bindingPropertyNames, null);
        this.bindingPropertyNames = bindingPropertyNames;
    }

    private void initPropertyChangeSupport(Object bean) {
        PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(bean);
        propertyChangeSupport.addPropertyChangeListener(new JavaBeansPropertyChangeListenerAdapter(context));
        this.propertyChangeSupport = propertyChangeSupport;
    }

    private void initBinding(Class<?> beanClass, String prefix, Map<String, String> bindingPropertyNames, String nestedPath) {
        if (isCandidateClass(beanClass)) {
            PropertyDescriptor[] descriptors = BeanUtils.getPropertyDescriptors(beanClass);
            int descriptorSize = descriptors.length;
            for (int i = 0; i < descriptorSize; i++) {
                PropertyDescriptor descriptor = descriptors[i];
                if (isCandidateProperty(descriptor)) {
                    String propertyName = descriptor.getName();
                    Class<?> propertyType = descriptor.getPropertyType();
                    String configurationPropertyName = prefix + "." + toDashedForm(propertyName);
                    String propertyPath = nestedPath == null ? propertyName : nestedPath + "." + propertyName;
                    bindingPropertyNames.put(configurationPropertyName, propertyPath);
                    initBinding(propertyType, configurationPropertyName, bindingPropertyNames, propertyPath);
                }
            }
        }
    }

    private boolean isCandidateProperty(PropertyDescriptor descriptor) {
        Method readMethod = descriptor.getReadMethod();
        return readMethod == null ? true : !Object.class.equals(readMethod.getDeclaringClass());
    }

    private boolean isCandidateClass(Class<?> beanClass) {
        if (ClassUtils.isPrimitiveOrWrapper(beanClass)) {
            return false;
        }
        if (beanClass.isInterface() || beanClass.isEnum() || beanClass.isAnnotation() || beanClass.isArray() || beanClass.isSynthetic()) {
            return false;
        }
        String className = beanClass.getName();
        if (className.startsWith("java.") || className.startsWith("javax.")) {
            return false;
        }
        return true;
    }

    public void setProperty(ConfigurationProperty property, Object newValue) {
        ConfigurationPropertyName name = property.getName();
        String propertyName = getPropertyName(name);
        Object oldValue = getPropertyValue(propertyName);
        if (!Objects.deepEquals(oldValue, newValue)) {
            initializedBeanWrapper.setPropertyValue(propertyName, newValue);
            propertyChangeSupport.firePropertyChange(propertyName, oldValue, newValue);
            publishConfigurationPropertiesBeanPropertyChangedEvent(property, propertyName, oldValue, newValue);
        }
    }

    private void publishConfigurationPropertiesBeanPropertyChangedEvent(ConfigurationProperty property, String propertyName, Object oldValue, Object newValue) {
        context.publishEvent(new ConfigurationPropertiesBeanPropertyChangedEvent(bean, propertyName, oldValue, newValue, property));
    }

    private String getPropertyName(ConfigurationPropertyName name) {
        return bindingPropertyNames.get(name.toString());
    }

    public String getPrefix() {
        return prefix;
    }

    public Class<?> getBeanClass() {
        return initializedBeanWrapper.getWrappedClass();
    }

    public Object getPropertyValue(String name) {
        return initializedBeanWrapper.getPropertyValue(name);
    }

    public Object getInitializedBean() {
        return this.initializedBeanWrapper.getWrappedInstance();
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(listener);
    }
}
