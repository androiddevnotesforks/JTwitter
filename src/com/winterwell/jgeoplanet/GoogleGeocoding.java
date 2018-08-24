package com.winterwell.jgeoplanet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Map;

import com.winterwell.json.JSONArray;
import com.winterwell.json.JSONException;
import com.winterwell.json.JSONObject;
import com.winterwell.utils.web.WebUtilsTest;

import winterwell.jtwitter.InternalUtils;
import winterwell.jtwitter.TwitterException;
import winterwell.jtwitter.URLConnectionHttpClient;

/**
 * Sketch code for a Google-based alternative to Yahoo {@link GeoPlanet}.
 * 
 * @testedby {@link GoogleGeocodingTest}
 * @author daniel
 *
 */
public class GoogleGeocoding implements IGeoCode {


	@Override
	public Boolean matches(GeoCodeQuery query, IPlace place) {
		// TODO use GIS to do better! !st develop test cases.
		return InternalUtils.geoMatch(query, place);
	}
	
	
	long RATE_LIMIT_HIT;
	
	public GPlace getPlace(String string) throws GeoPlanetException {
		if (string==null) throw new NullPointerException();
		// compact whitespace
		string = string.replaceAll("\\s+", " ").trim();		
		if (string.isEmpty()) {
			throw new IllegalArgumentException();
		}
		Map<IPlace, Double> pmap = getPlace(new GeoCodeQuery(string));
		IPlace best = InternalUtils.getBest(pmap);
		return (GPlace) best;
	}
	
	@Override
	public Map<IPlace, Double> getPlace(GeoCodeQuery query) {
		// back off for 3 minutes if you hit a rate limit
		if (System.currentTimeMillis() - RATE_LIMIT_HIT < 180000/*3 minutes*/) {
			throw new TwitterException.PreEmptiveRateLimit("OVER_QUERY_LIMIT request not sent *TO GOOGLE* ("+query+")");			
		}
		String resp = null;
		try {
			URLConnectionHttpClient uc = new URLConnectionHttpClient();
			resp = uc.getPage("http://maps.googleapis.com/maps/api/geocode/json?sensor=false&address="
					+urlEncode(query.desc), null, false);
//			HttpClient hv = new HttpClient();
//			GetMethod gm = new GetMethod("http://maps.googleapis.com/maps/api/geocode/json?sensor=false&address="
//					+urlEncode(query.desc));
//			int code = hv.executeMethod(gm);
//			InputStream respStr = gm.getResponseBodyAsStream();
//			resp = read(respStr);
			if (resp.contains("OVER_QUERY_LIMIT")) {
				RATE_LIMIT_HIT = System.currentTimeMillis();
				throw new TwitterException.RateLimit("OVER_QUERY_LIMIT ("+query.desc+")");
			}
			JSONObject json = new JSONObject(resp);
			JSONArray rs = json.getJSONArray("results");
			if (rs.length()==0) {
				throw new PlaceNotFoundException(query.desc); 
			}
			
			// Let's try removing the stochastic element...
//			Map<IPlace, Double> map = new ArrayMap(rs.length());
//			for (JSONObject jsonObject : rs) {
//				GPlace p = new GPlace(jsonObject);
//				// Google is pretty good. but it will code anything (e.g. "my house")
//				double prob = 0.85/rs.length();
//				// And the US has towns named after everything.
//				// ??But Boston, US is bigger than Boston, UK!
//				if ("US".equals(p.getCountryCode())) {
//					prob *= 0.5;
//				}
//				map.put(p, prob);
//			}
			// ... and just saying "here's Google's first guess, be more specific if this isn't what you wanted"
			Map<IPlace, Double> map = Collections.singletonMap((IPlace)(new GPlace(rs.getJSONObject(0))), 0.85);
			return map;
		} catch (GeoPlanetException e) {
			throw e;
		} catch (Exception e) {		
			throw new GeoPlanetException(e);
		}
	}
	
	/**
	 * @param r Will be read and closed
	 * @return The contents of input
	 * @throws IOException 
	 */
	static String read(InputStream rs) throws IOException {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(rs));
			final int bufSize = 8192; // this is the default BufferredReader
			// buffer size
			StringBuilder sb = new StringBuilder(bufSize);
			char[] cbuf = new char[bufSize];
			while (true) {
				int chars = reader.read(cbuf);
				if (chars == -1)
					break;
				sb.append(cbuf, 0, chars);
			}
			return sb.toString();
		} finally {
			rs.close();
		}
	}


	/**
	 * URL encode
	 * 
	 * @param x can be null
	 * @testedby {@link WebUtilsTest#testUrlEncode()}
	 */
	public static String urlEncode(Object x) {
		if (x == null)
			return "";
		String s = String.valueOf(x);
		try {
			s = URLEncoder.encode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			s = URLEncoder.encode(s);
		}
		s = s.replace("+", "%20"); // + for " " seems to be out of date.
		return s;
	}
	

	public static class GPlace implements IPlace {

		private String name;
		private String country;
		private Location centroid;
		private BoundingBox bbox;
		private String[] types;
		
		public String getCountryCode() {
			return new ISO3166().getCountryCode(country);
		}
		
		public Location getCentroid() {
			return centroid;
		}
		
		@Override
		public Class<? extends IGeoCode> getGeoCoder() {		
			return GoogleGeocoding.class;
		}
		
		@Override
		public IPlace getParent() {
			// TODO Is this available??
			return null;
		}
		
		public GPlace(JSONObject json) throws JSONException {
			JSONArray addr = json.getJSONArray("address_components");
			boolean probably_a_city = false;
			if (addr.length()==0) {
				name = "";
				country = "";
			} else {
				JSONObject addr0 = addr.getJSONObject(0);				
				name = addr0.getString("long_name");
				// guard against country=London!
				// Note: the country component isn't necc the last address element
				for(int i=0; i<addr.length(); i++) {
					JSONObject addr_i = addr.getJSONObject(i);
					JSONArray iTypes = addr_i.optJSONArray("types");
					if (iTypes==null || iTypes.length()==0) continue;
					// TODO detect cities here by e.g. types":["postal_town"]
					if (IPlace.TYPE_COUNTRY.equals(iTypes.get(0))) {
						country = addr_i.getString("long_name");
						break;
					}
				}
			}
			// geometry
			JSONObject geometry = json.getJSONObject("geometry");
			if (geometry!=null) {
				JSONObject locn = geometry.getJSONObject("location");
				if (locn!=null) {
					centroid = new Location(locn.getDouble("lat"), locn.getDouble("lng"));
				}
				JSONObject bounds = geometry.optJSONObject("bounds");
				if (bounds==null) bounds = geometry.optJSONObject("viewport");
				if (bounds!=null) {
					JSONObject ne = bounds.getJSONObject("northeast");
					JSONObject sw = bounds.getJSONObject("southwest");
					Location northEast = new Location(ne.getDouble("lat"), ne.getDouble("lng"));
					Location southWest = new Location(sw.getDouble("lat"), sw.getDouble("lng"));
					bbox = new BoundingBox(northEast, southWest);
					if (centroid==null) {
						// crude, but what the hell
						centroid = bbox.getCenter();
					}
				}
			}
			// type
			JSONArray _types = json.optJSONArray("types");
			if (_types==null) {
				this.types = new String[0];
			} else {
				int n= _types.length();
				this.types = new String[n];
				for(int i=0; i<n; i++) {
					this.types[i] = _types.getString(i);
				}
			}
			
		}

		@Override
		public String getUID() {
			// Hm, there's no ID...
			return name+getType()+centroid+"@googlemaps";
		}
		
		@Override
		public String toString() {
			return name;
		}
		
		public String getName() {
			return name;
		}

		public String getCountry() {
			return country;
		}

		@Override
		public BoundingBox getBoundingBox() {
			return bbox;
		}

		// TODO recognise city somehow!
		@Override
		public String getType() {
			if (types.length==0) return null;
//			if (types.length==1) {
				return types[0];
//			}
		}
		
	}
}
