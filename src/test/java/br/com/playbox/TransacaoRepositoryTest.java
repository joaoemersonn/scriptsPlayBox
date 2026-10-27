package br.com.playbox;

import br.com.playbox.repository.TransacaoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest
class TransacaoRepositoryTest {

    @Autowired
    private TransacaoRepository transacaoRepository;

    @Test
    void testFindFilteredWithNulls() {
        assertDoesNotThrow(() -> {
            transacaoRepository.findFiltered(
                    LocalDateTime.now().minusDays(30),
                    LocalDateTime.now(),
                    null,
                    null,
                    null,
                    null,
                    null
            );
        });
    }
}
