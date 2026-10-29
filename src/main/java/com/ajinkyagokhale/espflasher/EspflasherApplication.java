package com.ajinkyagokhale.espflasher;

import com.ajinkyagokhale.espflasher.ui.FlasherApp;
import javafx.application.Application;

import java.util.Locale;

public class EspflasherApplication {

	public static void main(String[] args) {
		// Botões padrão do JavaFX (OK, Cancelar...) em português
		Locale.setDefault(Locale.forLanguageTag("pt-BR"));
		Application.launch(FlasherApp.class, args);
	}

}