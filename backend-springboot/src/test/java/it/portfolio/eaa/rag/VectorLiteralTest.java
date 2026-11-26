package it.portfolio.eaa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VectorLiteralTest {

    @Test
    void formattaIlVettoreNelFormatoTestualeDiPgvector() {
        assertThat(DocumentChunkVectorStore.toVectorLiteral(new float[] {0.5f, -1f, 2.25f})).isEqualTo("[0.5,-1.0,2.25]");
    }
}
