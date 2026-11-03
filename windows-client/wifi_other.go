//go:build !windows

package main

// Versões vazias para compilar e testar fora do Windows.

import "errors"

func scanNetworks() (map[string]int, error) { return nil, errors.New("só no Windows") }
func joinWifi(ssid string) error            { return errors.New("só no Windows") }
func leaveWifi()                            {}

type wifiStatus struct {
	SSID    string `json:"ssid"`
	Signal  int    `json:"signal"`
	Channel int    `json:"channel"`
	RxMbps  string `json:"rxMbps"`
	Note    string `json:"note,omitempty"`
}

func currentWifi() wifiStatus           { return wifiStatus{Signal: -1} }
func openBrowser(url string)            {}
func getAutoConfigURL() string          { return "" }
func setAutoConfigURL(url string) error { return nil }

func hotspot(action string) error { return errors.New("só no Windows") }
