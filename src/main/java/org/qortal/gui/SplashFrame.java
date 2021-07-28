package org.qortal.gui;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;

import javax.swing.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SplashFrame {

	protected static final Logger LOGGER = LogManager.getLogger(SplashFrame.class);

	private static SplashFrame instance;
	private JFrame splashDialog;

	@SuppressWarnings("serial")
	public static class SplashPanel extends JPanel {
		private BufferedImage image;

		private String defaultSplash = "Qlogo_512.png";

		public SplashPanel() {
			image = Gui.loadImage(defaultSplash);

			setOpaque(false);
			setLayout(new GridBagLayout());
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			g.drawImage(image, 0, 0, getWidth(), getHeight(), this);
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(500, 500);
		}
	}

	private SplashFrame() {
		this.splashDialog = new JFrame();

		List<Image> icons = new ArrayList<>();
		icons.add(Gui.loadImage("icons/icon16.png"));
		icons.add(Gui.loadImage("icons/icon32.png"));
		icons.add(Gui.loadImage("icons/icon32c.png"));
		icons.add(Gui.loadImage("icons/icon32m.png"));
		icons.add(Gui.loadImage("icons/icon32n.png"));
		icons.add(Gui.loadImage("icons/icon64.png"));
		icons.add(Gui.loadImage("icons/icon128.png"));
		this.splashDialog.setIconImages(icons);

		this.splashDialog.getContentPane().add(new SplashPanel());
		this.splashDialog.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		this.splashDialog.setUndecorated(true);
		this.splashDialog.pack();
		this.splashDialog.setLocationRelativeTo(null);
		this.splashDialog.setBackground(new Color(0,0,0,0));
		this.splashDialog.setVisible(true);
	}

	public static SplashFrame getInstance() {
		if (instance == null)
			instance = new SplashFrame();

		return instance;
	}

	public void setVisible(boolean b) {
		this.splashDialog.setVisible(b);
	}

	public void dispose() {
		this.splashDialog.dispose();
	}

}
