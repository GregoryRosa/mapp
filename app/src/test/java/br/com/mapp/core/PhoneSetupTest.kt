package br.com.mapp.core

import br.com.mapp.core.status.PhoneBrand
import br.com.mapp.core.status.PhoneSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupTest {

    @Test fun fabricantes_com_inicio_automatico() {
        assertEquals(PhoneBrand.OPPO, PhoneBrand.of("realme", "realme"))
        assertEquals(PhoneBrand.OPPO, PhoneBrand.of("OPPO"))
        assertEquals(PhoneBrand.OPPO, PhoneBrand.of("OnePlus"))
        assertEquals(PhoneBrand.XIAOMI, PhoneBrand.of("Xiaomi", "Redmi"))
        assertEquals(PhoneBrand.XIAOMI, PhoneBrand.of("unknown", "POCO"))
        assertEquals(PhoneBrand.VIVO, PhoneBrand.of("vivo"))
        assertEquals(PhoneBrand.HUAWEI, PhoneBrand.of("HUAWEI"))
        assertEquals(PhoneBrand.HONOR, PhoneBrand.of("HONOR"))
    }

    @Test fun samsung_motorola_e_pixel_nao_pedem() {
        for (m in listOf("samsung", "motorola", "Google", null)) {
            val brand = PhoneBrand.of(m)
            assertEquals(PhoneBrand.OTHER, brand)
            assertFalse(PhoneSetup.needsAutoLaunch(brand))
            assertTrue(PhoneSetup.autoLaunchScreens(brand).isEmpty())
        }
    }

    @Test fun cada_fabricante_tem_tela_e_caminho() {
        for (brand in PhoneBrand.entries.filter { it != PhoneBrand.OTHER }) {
            assertTrue(PhoneSetup.needsAutoLaunch(brand))
            assertTrue("$brand sem tela", PhoneSetup.autoLaunchScreens(brand).isNotEmpty())
            assertTrue("$brand sem caminho", PhoneSetup.autoLaunchPath(brand).contains("MApp"))
        }
    }

    @Test fun realme_usa_o_nome_da_tela_do_print() {
        // Print de 23/11/2026: "Início automático em segundo plano".
        assertTrue(PhoneSetup.autoLaunchPath(PhoneBrand.OPPO).contains("Início automático em segundo plano"))
        assertTrue(PhoneSetup.UNUSED_PATH.contains("Pausar atividade do app quando sem uso"))
    }
}
