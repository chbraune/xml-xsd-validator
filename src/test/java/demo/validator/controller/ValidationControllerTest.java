package demo.validator.controller;

import demo.validator.dto.ValidationError;
import demo.validator.dto.ValidationResponse;
import demo.validator.exception.GlobalExceptionHandler;
import demo.validator.exception.ValidationException;
import demo.validator.service.XmlValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit test for {@link ValidationController} using standalone MockMvc.
 *
 * <p>No Spring application context is started. MockMvc is built with
 * {@link MockMvcBuilders#standaloneSetup(Object...)} which only requires
 * {@code spring-test} – available via {@code spring-boot-starter-test}.
 * The {@link GlobalExceptionHandler} is registered explicitly so that
 * exception-handling behaviour is also covered.
 */
@ExtendWith(MockitoExtension.class)
class ValidationControllerTest {

    @Mock
    private XmlValidationService validationService;

    @InjectMocks
    private ValidationController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // -------------------------------------------------------------------------
    // Test fixture
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
    // Schema violations – structured error objects in the response
    // -------------------------------------------------------------------------

    @Test
    void postInvalidXml_shouldReturn200WithStructuredErrors() throws Exception {
        List<ValidationError> errors = List.of(
                ValidationError.of("order.items.item.quantity", "abc",
                        "Value 'abc' is not valid for xs:positiveInteger")
        );
        when(validationService.validate(anyString()))
                .thenReturn(ValidationResponse.failed(errors));

        mockMvc.perform(post("/api/xml/validate")
                        .contentType(MediaType.TEXT_XML)
                        .content(VALID_XML))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("order.items.item.quantity"))
                .andExpect(jsonPath("$.errors[0].value").value("abc"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Value 'abc' is not valid for xs:positiveInteger"));
    }

    @Test
    void postInvalidXml_errorWithNullFieldAndValue_shouldOmitNullsInJson() throws Exception {
        List<ValidationError> errors = List.of(
                ValidationError.ofMessage("XML is not well-formed: unexpected EOF")
        );
        when(validationService.validate(anyString()))
                .thenReturn(ValidationResponse.failed(errors));

        mockMvc.perform(post("/api/xml/validate")
                        .contentType(MediaType.TEXT_XML)
                        .content("broken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("XML is not well-formed: unexpected EOF"));
    }

    // -------------------------------------------------------------------------
    // Technical failures → GlobalExceptionHandler → 422
    // -------------------------------------------------------------------------

    @Test
    void serviceThrowsValidationException_shouldReturn422() throws Exception {
        when(validationService.validate(anyString()))
                .thenThrow(new ValidationException("XML cannot be parsed: internal SAX error"));

        mockMvc.perform(post("/api/xml/validate")
                        .contentType(MediaType.TEXT_XML)
                        .content("broken"))
                .andExpect(status().isUnprocessableEntity());
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
