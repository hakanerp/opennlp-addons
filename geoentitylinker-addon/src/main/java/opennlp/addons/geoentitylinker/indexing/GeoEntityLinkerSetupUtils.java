/*
 * Copyright 2013 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.addons.geoentitylinker.indexing;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import opennlp.addons.geoentitylinker.AdminBoundaryContextGenerator;
import opennlp.addons.geoentitylinker.scoring.ModelBasedScorer;

import opennlp.tools.doccat.DoccatModel;
import opennlp.tools.doccat.DocumentCategorizerME;
import opennlp.tools.doccat.DocumentSample;
import opennlp.tools.doccat.DocumentSampleStream;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;

/**
 *
 * Tools for setting up GeoEntityLinker gazateers and doccat scoring model
 */
@Deprecated
public class GeoEntityLinkerSetupUtils {
  private static final int RADIUS = 200;
  public static ModelBasedScorer scorer;

  static {
    scorer = new ModelBasedScorer();
  }

  /**
   * Generates the lucene indexes of the USGS and GEONAMES gazateers.
   *
   * @param outputIndexDir    the destination directory of the index. Must be a
   *                          directory
   * @param gazateerInputData the input data file. Must be in geonames gaz
   *                          format, or USGS format
   * @param type              the type, USGS, or GEONAMES
   */
  public static void createLuceneIndex(File outputIndexDir, File gazateerInputData, GazetteerIndexer.GazType type) {
    GazetteerIndexer indexer = new GazetteerIndexer();
    try {
      indexer.index(outputIndexDir, gazateerInputData, type);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  /**
   * Generates a doccat model from proximal features generated from surrounding
   * context of country mentions. This model is used as a basis for a score
   * called coutrymodel, which takes the context from around a toponym, and uses
   * this model to return a score for the country code of the toponym hit in the
   * gazateer.
   *
   * @param documents         A list of document texts, for best results try to
   *                          ensure each country you care about will be well
   *                          represented in the collection
   * @param annotationOutFile the location where the annotated doccat text file
   *                          will be stored
   * @param modelOutFile      the location where the doccat model will be stored
   * @param properties        the properties where the country context object
   *                          will find it's country data from this property:
   *                          opennlp.geoentitylinker.countrycontext.filepath
   * @throws IOException
   */
  public static void buildCountryContextModel(Collection<String> documents, File annotationOutFile, File modelOutFile, EntityLinkerProperties properties) throws Exception {
    AdminBoundaryContextGenerator context = new AdminBoundaryContextGenerator(properties);
    FileWriter writer = new FileWriter(annotationOutFile, true);
    System.out.println("processing " + documents.size() + " documents");
    for (String docText : documents) {
      System.out.append(".");
      Map<String, Set<Integer>> regexfind = context.regexfind(docText);
      Map<String, ArrayList<String>> modelCountryContext = modelCountryContext(docText, context, RADIUS);
      for (String key : modelCountryContext.keySet()) {
        for (String wordbag : modelCountryContext.get(key)) {
          writer.write(key + " " + wordbag + "\n");
        }
      }
    }
    System.out.println("Document processing complete. Writing training data to " + annotationOutFile.getAbsolutePath());
    writer.close();
    System.out.println("Building Doccat model...");
    DoccatModel model = null;

    InputStream dataIn = new FileInputStream(annotationOutFile);
    try {
    
      ObjectStream<String> lineStream = new PlainTextByLineStream(dataIn, "UTF-8");
      ObjectStream<DocumentSample> sampleStream = new DocumentSampleStream(lineStream);

      model = DocumentCategorizerME.train("en", sampleStream);
      OutputStream modelOut = new BufferedOutputStream(new FileOutputStream(modelOutFile));
      model.serialize(modelOut);
      System.out.println("Model complete!");
    } catch (IOException e) {
      // Failed to read or parse training data, training failed
      e.printStackTrace();
    }

  }

  /**
   * generates proximal wordbags within the radius of a country mention within
   * the doctext based on the country context object
   *
   *
   * @param docText
   * @param additionalContext
   * @param radius
   * @return
   */
  private static Map<String, ArrayList<String>> modelCountryContext(String docText, AdminBoundaryContextGenerator additionalContext, int radius) {
    Map<String, ArrayList< String>> featureBags = new HashMap<>();
    Map<String, Set<Integer>> countryMentions = additionalContext.getCountryMentions();
    /**
     * iterator over the map that contains a mapping of every country code to
     * all of its mentions in the document
     */
    for (String code : countryMentions.keySet()) {
      /**
       * for each mention, collect features from around each mention, then
       * consolidate the features into another map
       */
      for (int mentionIdx : countryMentions.get(code)) {
        String chunk = scorer.getTextChunk(mentionIdx, docText, radius);
        //   Collection<String> extractFeatures = super.extractFeatures(chunk.split(" "));
        if (featureBags.containsKey(code)) {
          featureBags.get(code).add(chunk);
        } else {
          ArrayList<String> newlist = new ArrayList<>();
          newlist.add(chunk);
          featureBags.put(code, newlist);
        }
      }
    }
    return featureBags;
  }
}