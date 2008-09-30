/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or 
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package joshua.decoder.hypergraph;

import joshua.decoder.Decoder;
import joshua.decoder.Symbol;
import joshua.decoder.ff.tm.TMGrammar;
import joshua.decoder.ff.tm.TMGrammar_Memory;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.ff.tm.TMGrammar_Memory.Rule_Memory;
import joshua.decoder.hypergraph.HyperGraph;
import joshua.decoder.hypergraph.HyperGraph.Deduction;
import joshua.decoder.hypergraph.HyperGraph.Item;
import joshua.util.FileUtility;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * this class implements functions of writting/reading hypergraph on disk
 * 
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate$
 */
//Bottom-up
//line: SENTENCE_TAG, sent_id, sent_len, num_items, num_deductions (in average, num_deductions is about 10 times larger than the num_items, which is in average about 4000)
//line: ITEM_TAG, item id, i, j, lhs, num_deductions, tbl_state;
//line: best_cost, num_items, item_ids, rule id,  OOV-Non-Terminal (optional), OOV (optional),
public class DiskHyperGraph {
//	shared by many hypergraphs
	public HashMap tbl_associated_grammar = new HashMap();
	//static int cur_rule_id=1;
	BufferedWriter writer_out =null;//write items
	BufferedReader reader_in =null;//read items
	
	HyperGraphPruning forest_pruner=null;
	
	String start_line=null; //this will be set if the previous sentence is skipped
	HashMap tbl_selected_sents = null;
	
//	shared by a single hypergraph
	HashMap tbl_item_2_id =new HashMap();//map item to id, used for saving hypergraph
	HashMap tbl_id_2_item =new HashMap();//map id to item, used for reading hypergraph 
	int cur_item_id=1;
	int total_num_deducts = 0;//number of deductions in the hypergraph
	
// static variables	
	static String SENTENCE_TAG ="#SENT: ";
	static String ITEM_TAG ="#I";
   //static String DEDUCTION_TAG ="#D";	
   //static String OPTIMAL_DEDUCTION_TAG ="#D*";
    
    static String ITEM_STATE_TAG= " ST ";
    static String NULL_ITEM_STATE= "nullstate";    
    static int NULL_RULE_ID = -1;//three kinds of rule: regular rule (id>0); oov rule (id=0), and null rule (id=-1)
    static int OOV_RULE_ID = 0;
    
    static String RULE_TBL_SEP =" -LZF- ";
    
    public static void main(String[] args) {	
		Symbol.add_global_symbols(true);		
		String f_hypergraphs="C:\\Users\\zli\\Documents\\mt03.src.txt.ss.nbest.hg.items";
		String f_rule_tbl="C:\\Users\\zli\\Documents\\mt03.src.txt.ss.nbest.hg.rules";
	
		String config_file="C:\\data_disk\\java_work_space\\example.config.javalm";
		ArrayList l_models = Decoder.init_models(config_file,null);
		BufferedWriter nbest_out = FileUtility.getWriteFileStream("C:\\Users\\zli\\Documents\\hg.mt03.nbest");
		int total_num_sent = 919;//919	
		
		long start_time = System.currentTimeMillis();		
		
		DiskHyperGraph dhg = new DiskHyperGraph();
		KbestExtraction kbest_extractor = new KbestExtraction();
		
		dhg.init_read(f_hypergraphs, f_rule_tbl, null);			
		for(int sent_id=0; sent_id < total_num_sent; sent_id ++){
			System.out.println("############Process sentence " + sent_id);
			HyperGraph hg = dhg.read_hyper_graph();				
			//hg.lazy_k_best_extract(l_models, 300, true, sent_id, nbest_out, false, true);//nbest extracvtion				
			kbest_extractor.lazy_k_best_extract_hg(hg, l_models, 300, true, sent_id, nbest_out, false, true);//nbest extracvtion
		}			
	
		FileUtility.close_write_file(nbest_out);
		System.out.println("perceptron: " + (System.currentTimeMillis()-start_time)/1000);	
	}
	
//for writting hyper-graph: (1) saving each hyper-graph; (2) remember each regualar rule used; (3) dump the rule jointly (in case parallel decoding)      
    public void init_write(String f_items, boolean use_forest_pruning, double threshold){
    	writer_out =  FileUtility.handle_null_file(f_items);
    	if(use_forest_pruning==true)  forest_pruner = new HyperGraphPruning(true, threshold, threshold, 1, 1);//TODO
    }    
 
    public void init_read(String f_hypergraphs, String f_rule_tbl, HashMap tbl_sent_sel_tbl){
    	reader_in = FileUtility.getReadFileStream(f_hypergraphs);
    	tbl_selected_sents = tbl_sent_sel_tbl;
    	reload_rule_tbl(f_rule_tbl);
    }    
   
    public void reload_rule_tbl(String f_rule_tbl){
    	//read rule tables    	
    	System.out.println("Reading rules from file " + f_rule_tbl);
    	tbl_associated_grammar.clear();
    	BufferedReader reader_rules = FileUtility.getReadFileStream(f_rule_tbl);
    	String line = null;    	
    	while((line = FileUtility.read_line_lzf(reader_rules))!=null){
    		//rule_id owner RULE_TBL_SEP rule
    		String[] fds = line.split(RULE_TBL_SEP);
    		if(fds.length!=2){System.out.println("wrong RULE line"); System.exit(0);}
    		String[] wrds = line.split("\\s+");
    		int rule_id = new Integer(wrds[0]);
    		int default_owner = Symbol.add_terminal_symbol(wrds[1]);
    		Rule rule = new TMGrammar_Memory.Rule_Memory(rule_id, fds[1], default_owner);//TODO: the stateless cost if not correct due to estimate_rule
    		tbl_associated_grammar.put(rule_id, rule);
    	}
    	FileUtility.close_read_file(reader_rules);
    }    
    
	public void save_hyper_graph(HyperGraph hg){	
		reset_states();
		if(forest_pruner!=null) forest_pruner.pruning_hg(hg);
		construct_item_id_tbl(hg);
		System.out.println("Number of Items is: " +tbl_item_2_id.size());
		//line: SENTENCE_TAG, sent_id, sent_len, num_item, num_deduct
		FileUtility.write_lzf(writer_out, SENTENCE_TAG + hg.sent_id  + " "  + hg.sent_len  + " "  + tbl_item_2_id.size() + " "  + total_num_deducts + "\n" );
		
		
		//we save the hypergraph in a bottom-up way: so that reading is easy
		if(tbl_id_2_item.size()!=tbl_item_2_id.size()){System.out.println("Number of Items not equal"); System.exit(0);}
		for(int i=1; i<= tbl_id_2_item.size(); i++){
			Item it = (Item) tbl_id_2_item.get(i);
			save_item(writer_out,it);
		}
		if(forest_pruner!=null) forest_pruner.clear_state();
		
	}	
    
	private boolean should_skip_sent(String s_line){
		if(tbl_selected_sents==null)return false;
		String[] fds = s_line.split("\\s+");
		int sent_id = new Integer(fds[1]);	
		if(tbl_selected_sents.containsKey(sent_id)){
			return false;
		}else{
			return true;
		}
	}
	
	public HyperGraph read_hyper_graph(){
		reset_states();	
		//read first line: SENTENCE_TAG, sent_id, sent_len, num_items, num_deduct
		String line= null;
		if(start_line!=null)//the previous sentence is skipped
			line = start_line;
		else
			line = FileUtility.read_line_lzf(reader_in);
		start_line=null;
		
		if(line.startsWith(SENTENCE_TAG)!=true){System.out.println("wrong sent tag line: " + line); System.exit(0);}
		
		if(should_skip_sent(line)){//skip the hypergraph for this sentence
			while((line=FileUtility.read_line_lzf(reader_in))!=null){
				if(line.startsWith(SENTENCE_TAG)==true)break;
			}
			start_line=line;
			System.out.println("sentence is skipped");
			return null;
		}else{		
			String[] fds = line.split("\\s+");
			int sent_id = new Integer(fds[1]);
			int sent_len = new Integer(fds[2]);
			int num_items = new Integer(fds[3]);
			int num_deducts = new Integer(fds[4]);
			System.out.println("num_items: " + num_items + "; num_deducts: " + num_deducts);		
			for(int i=0; i< num_items; i++){
				read_item(reader_in);
			}
			//TODO check if the file reaches EOF, or if the num_deducts matches 
			
			//create hyper graph
			Item goal_item = (Item)tbl_id_2_item.get(num_items);
			if(goal_item==null){System.out.println("no goal item"); System.exit(0);}
			HyperGraph res = new HyperGraph(goal_item, num_items, num_deducts, sent_id, sent_len);
			return res;
		}
	}
	 
	public HashMap get_used_grammar_tbl(){
	   	return tbl_associated_grammar;
	}
	    
    public void write_rules_non_parallel(String f_rule_tbl){
    	BufferedWriter out_rules =  FileUtility.handle_null_file(f_rule_tbl);
    	System.out.println("writing rules");
    	for(Iterator it = tbl_associated_grammar.keySet().iterator(); it.hasNext(); ){
    		int rule_id = (Integer) it.next();
    		Rule rl = (Rule)tbl_associated_grammar.get(rule_id);
    		save_rule(out_rules, rl, rule_id);
    	}
    	FileUtility.flush_lzf(out_rules);
    	FileUtility.close_write_file(out_rules);
    }

    public void write_rules_parallel(BufferedWriter out_rules, HashMap tbl_done){
    	System.out.println("writing rules in a partition");
    	for(Iterator it = tbl_associated_grammar.keySet().iterator(); it.hasNext(); ){
    		int rule_id = (Integer) it.next();
    		if(tbl_done.containsKey(rule_id)==false){
    			tbl_done.put(rule_id, 1);
    			Rule rl = (Rule)tbl_associated_grammar.get(rule_id);
    			save_rule(out_rules, rl, rule_id);
    		}
    	}
    	FileUtility.flush_lzf(out_rules);
    }
	
    private static void save_rule(BufferedWriter out_rules, Rule rl, int rule_id){
    	String str_rule = rl.toString();
		String owner = Symbol.get_string(rl.owner);
		//rule_id owner RULE_TBL_SEP rule
		FileUtility.write_lzf(out_rules, rule_id +" " + owner  + RULE_TBL_SEP  +str_rule  +"\n");//note (inverse): rule-id RULE_TBL_SEP rule
	}
    
	private void reset_states(){
	  	tbl_item_2_id.clear();
	   	tbl_id_2_item.clear();
	   	cur_item_id=1;
	   	total_num_deducts=0;
	}
	
	private  void save_item(BufferedWriter out, Item item){		
		StringBuffer res = new StringBuffer();		
		//line: ITEM_TAG, item id, i, j, lhs, num_deductions, tbl_state;
		res.append(ITEM_TAG); res.append(" "); res.append((Integer)tbl_item_2_id.get(item)); res.append(" ");
		res.append(item.i);	res.append(" "); res.append(item.j);//i,j
		res.append(" "); res.append(Symbol.get_string(item.lhs)); res.append(" ");//lhs
		
		if(item.l_deductions==null)//TODO
			res.append(0);
		else
			res.append(item.l_deductions.size());
	
		res.append(ITEM_STATE_TAG);
		//signature (created from HashMap tbl_states)
		if(item.tbl_states!=null)
			res.append(item.get_string_from_state_tbl(item.tbl_states));
		else
			res.append(NULL_ITEM_STATE);
		res.append("\n");		
		FileUtility.write_lzf(out, res.toString());
			
		//for each hyper-edge
		if(item.l_deductions!=null)
			for(int i=0; i< item.l_deductions.size(); i++){
				Deduction dt = (Deduction)  item.l_deductions.get(i);
				save_deduction(out, item, dt);
			}		
		FileUtility.flush_lzf(out);
	}
	
	private  Item read_item(BufferedReader in){		
		//line: ITEM_TAG, item id, i, j, lhs, num_deductions, ITEM_STATE_TAG, item_state;
		String line=FileUtility.read_line_lzf(in);		
		//if(line.startsWith(ITEM_TAG)!=true){System.out.println("wrong item tag"); System.exit(0);}
		String[] fds = line.split(ITEM_STATE_TAG);
		if(fds.length!=2){System.out.println("wrong item line"); System.exit(0);}
		String[] wrds1 = fds[0].split("\\s+");
		int item_id = new Integer(wrds1[1]);
		int i = new Integer(wrds1[2]);
		int j = new Integer(wrds1[3]);
		int lhs = Symbol.add_non_terminal_symbol(wrds1[4]);
		int num_deductions = new Integer(wrds1[5]);
		
		HashMap tbl_states = null;//item state: signature (created from HashMap tbl_states)
		if(fds[1].compareTo(NULL_ITEM_STATE)!=0){
			tbl_states = Item.get_state_tbl_from_string(fds[1]);//create statte
		}	
		
		ArrayList<Deduction> l_deductions = null;
		Deduction best_deduction=null;
		double best_cost= Double.POSITIVE_INFINITY;
		if(num_deductions>0){
			l_deductions = new ArrayList<Deduction>();	
			for(int t=0; t<num_deductions; t++){				
				Deduction dt = read_deduction(in);
				l_deductions.add(dt);
				if( dt.best_cost<best_cost) {best_cost=dt.best_cost; best_deduction = dt;} 
			}				
		}

		Item item = new Item(i, j, lhs,  l_deductions, best_deduction, tbl_states);
		tbl_id_2_item.put(item_id, item);
		return item;
	}
	
	private void save_deduction(
		BufferedWriter out, Item item, Deduction deduction
	) {
		//get rule id
		int rule_id = NULL_RULE_ID;
		final Rule deduction_rule = deduction.get_rule();
		if (null != deduction_rule) {
			rule_id = deduction_rule.getRuleID();
			if	(! deduction_rule.isOutOfVocabularyRule()) {
				tbl_associated_grammar.put(rule_id, deduction_rule); //remember used regular rule
			}
		}
		
		StringBuffer res = new StringBuffer();		
		//line: best_cost, num_items, item_ids, rule id,  OOV-Non-Terminal (optional), OOV (optional),
		/*if(cur_d==item.best_deduction)
			res.append(OPTIMAL_DEDUCTION_TAG);//best deduction flag "*"
		else
			res.append(DEDUCTION_TAG);
		res.append(String.format(" %.4f ", deduction.best_cost));*/
		res.append(String.format("%.4f ", deduction.best_cost));
		//res.append(" "); res.append(cur_d.best_cost); res.append(" ");//this 1.2 faster than the previous statement
		
		//res.append(String.format("%.4f ", cur_d.get_transition_cost(false)));
		//res.append(cur_d.get_transition_cost(false)); res.append(" ");//this 1.2 faster than the previous statement, but cost 1.4 larger disk space
		
		if (null == deduction.get_ant_items()) {
			res.append(0);
		} else {
			final int qty_items = deduction.get_ant_items().size();
			res.append(qty_items);
			for (int i = 0; i < qty_items; i++) {
				res.append(" ");
				res.append((Integer)tbl_item_2_id.get(
					deduction.get_ant_items().get(i) ));
			}
		}
		res.append(" ");
		res.append(rule_id);
		if (rule_id == OOV_RULE_ID) {
			res.append(" "); res.append(Symbol.get_string(deduction_rule.lhs));
			res.append(" "); res.append(Symbol.get_string(deduction_rule.english));
		}
		res.append("\n");
		FileUtility.write_lzf(out, res.toString());
	}
	
	
	//assumption: has tbl_associated_grammar and tbl_id_2_item
	private Deduction read_deduction(BufferedReader in){		
		//line: flag, best_cost, num_items, item_ids, rule id,  OOV-Non-Terminal (optional), OOV (optional),		
		String line=FileUtility.read_line_lzf(in);
		String[] fds = line.split("\\s+");
		
		/*//flag
		if(fds[0].startsWith(DEDUCTION_TAG)!=true){	System.out.println("wrong deduction start line"); System.exit(0);}
		if(fds[0].compareTo(DEDUCTION_TAG)!=0)
			is_best[0]=true;
		*/
		
		//best_cost transition_cost num_items item_ids
		double best_cost =new Double(fds[0]);
		ArrayList<Item> l_ant_items=null;
		int num_ant_items =new Integer(fds[1]);	
		if(num_ant_items>0){
			l_ant_items = new ArrayList<Item>();
			for(int t=0; t< num_ant_items; t++){
				int item_id = new Integer(fds[2+t]);
				Item t_it = (Item)tbl_id_2_item.get(item_id);
				if(t_it==null){System.out.println("item is null for id: " + item_id); System.exit(0);}
				l_ant_items.add(t_it);
			}
		}		
		//rule_id
		Rule rule = null;
		int r_id = new Integer(fds[2+num_ant_items]);
		if(r_id!=NULL_RULE_ID){
			if(r_id!=OOV_RULE_ID){
				rule = (Rule)tbl_associated_grammar.get(r_id);
				if(rule==null){System.out.println("rule is null but id is " + r_id); System.exit(0);}	
				//System.out.println("nonoov rule str: " + str_rule + "; arity: " + rule.arity);
			}else{
				int lhs = Symbol.add_non_terminal_symbol(fds[3+num_ant_items]);
				int french_symbol = Symbol.add_terminal_symbol(fds[4+num_ant_items]);
				rule = new TMGrammar_Memory.Rule_Memory(lhs, french_symbol, Symbol.UNTRANS_SYM_ID);//TODO: change owner
				//System.out.println("oov rule str: " + str_rule + "; arity: " + rule.arity);
			}
			
		}	
		Deduction dt = new Deduction(rule, best_cost, null, l_ant_items);
		return dt;		
	}
	
	
//################# assigne ID to items##################
//	the item id is increased bottom-up: the lower the item is at, the smaller id it has
	private void construct_item_id_tbl(HyperGraph hg){
		reset_states();	
		construct_item_id_tbl(hg.goal_item);
	}
	private void construct_item_id_tbl(Item it){
		if(tbl_item_2_id.containsKey(it))
			return;
		//first: assign id to all my ants
		for(Deduction dt : it.l_deductions){
			total_num_deducts++;
			if(dt.get_ant_items()!=null)
				for(Item ant_it : dt.get_ant_items())
					construct_item_id_tbl(ant_it);
		}		
		//second: assign id to myself
		tbl_id_2_item.put(cur_item_id, it);
		tbl_item_2_id.put(it,cur_item_id++);//ant get smaller id
	}
//	################# assigne ID to items##################	
}