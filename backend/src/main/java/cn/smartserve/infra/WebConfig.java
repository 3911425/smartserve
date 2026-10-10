package cn.smartserve.infra;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Value("${smartserve.data-path}")
  String path;

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry r) {
    r
      .addResourceHandler("/files/images/**")
      .addResourceLocations(
        Path.of(path, "images").toAbsolutePath().toUri().toString()
      );
  }
}
