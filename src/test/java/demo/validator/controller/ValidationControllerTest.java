package demo.validator.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import demo.validator.dto.ValidationResponse;
import demo.validator.exception.ValidationException;
import demo.validator.service.XmlValidationService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link ValidationController}.
 *
 * <p>Uses {@code @WebMvcTest} to load only the web layer.
 * {@link XmlValidationService} is replaced by a Mockito mock
 * via {@code @MockitoBean} (Spring Boot 4 / Spring Framework 6.2+).
 */
@WebMvcTest(ValidationController.class)
class ValidationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private XmlValidationService validationService;

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private static final String VALID_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <order>
                <orderId>ORD-001</orderId>
                <customer>Acme Corp</customer>
                <items>
                    <item>
                        <productId>PROD-1</productId>
                        <quantity>2</quantity>
                        <price>19.99</price>
                    </item>
                </items>
            </order>
            """;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void postValidXml_shouldReturn200AndValidTrue() throws Exception {
        when(validationService.validate(anyString())).thenReturn(ValidationResponse.ok());

        mockMvc.perform(post("/api/xml/validate")
                        .contentType(MediaType.TEXT_XML)
                        .content(VALID_XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Schema violations
    // -------------------------------------------------------------------------

    @Test
    void postInvalidXml_shouldReturn200AndValidFalse() throws Exception {
        List<String> errors = List.of("[ERROR] line 4, col 10: missing element 'customer'");
        when(validationService.validate(anyString()))
                .thenReturn(ValidationResponse.failed(errors));

        mockMvc.perform(post("/api/xml/validate")
                        .contentType(MediaType.TEXT_XML)
                        .content(VALID_XML))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").value("[ERROR] line 4, col 10: missing element 'customer'"));
    }

    // -------------------------------------------------------------------------
    // Technical failures
    // -------------------------------------------------------------------------

    @Test
    void serviceThrowsValidationException_shouldReturn422() throws Exception {
        when(validationService.validate(anyString()))
                .thenThrow(new ValidationException("XML cannot be parsed: unexpected EOF"));

        mockMvc.perform(post("/api/xml/validate")
                        .contentType(MediaType.TEXT_XML)
                        .content("broken xml"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("XML Processing Error"));
    }

    // -------------------------------------------------------------------------
    // HTTP-level errors
    // -------------------------------------------------------------------------

    @Test
    void wrongContentType_shouldReturn415() throws Exception {
        mockMvc.perform(post("/api/xml/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ }"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void applicationXmlContentType_shouldBeAccepted() throws Exception {
        when(validationService.validate(anyString())).thenReturn(ValidationResponse.ok());

        mockMvc.perform(post("/api/xml/validate")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(VALID_XML))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }
}
